package com.ideazlab.mongodesk

import com.mongodb.ConnectionString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.bson.*
import java.io.*
import java.net.URI
import java.net.URLEncoder
import java.nio.*
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal enum class DatabaseExportSource(val label: String) {
    DATABASE("Entire Database"), COLLECTION("Entire Collection/View"), QUERY("Current Query Result"), SELECTED("Selected Document")
}
internal enum class DatabaseImportMode(val label: String) { NEW("New database"), MERGE("Merge into existing database"), REPLACE("Replace matching collections") }
internal data class DatabaseTransferContext(val session: MongoSession, val database: String, val collection: String = "",
    val appliedFilter: String = "{}", val appliedPipeline: List<BsonDocument>? = null, val selected: BsonDocument? = null, val queryLoaded: Boolean = false)
internal data class ArchiveInfo(val gzip: Boolean, val collections: Map<String, List<String>>)

internal fun validateTransferDatabase(name: String) {
    if (name.isBlank() || name.toByteArray().size >= 64 || name.any { it.isISOControl() || it in "/\\. \"$*<>:|?" })
        throw InputError("Enter a valid database name (under 64 bytes, without spaces, dots, slashes, or wildcard characters).")
}

/** Remove the default database without changing authentication or connection options. */
internal fun databaseToolUri(config: ConnectionConfig, tunnelPort: Int? = null): String {
    val original = config.uri.trim()
    val parsed = ConnectionString(original)
    val uri = URI(original)
    val params = uri.rawQuery?.split('&')?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    val keys = params.map { it.substringBefore('=').lowercase() }
    if (parsed.credential != null && "authsource" !in keys) {
        params += "authSource=" + URLEncoder.encode(parsed.credential!!.source, Charsets.UTF_8).replace("+", "%20")
    }
    var authority = uri.rawAuthority ?: throw InputError("Invalid connection URI.")
    if (tunnelPort != null) {
        if (parsed.sslEnabled == true) throw InputError("Archive transfer over an SSH tunnel with TLS is not supported. Use a direct TLS connection, or export query results as Extended JSON.")
        authority = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" } + "127.0.0.1:$tunnelPort"
        if ("directconnection" !in keys) params += "directConnection=true"
    }
    return "${uri.scheme}://$authority/" + if (params.isEmpty()) "" else "?${params.joinToString("&")}" 
}

internal fun inspectDatabaseArchive(path: Path): ArchiveInfo {
    try {
        Files.newInputStream(path).buffered().use { raw ->
            raw.mark(2); val gzip = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset()
            val input: InputStream = if (gzip) GZIPInputStream(raw) else raw
            fun number(): Int {
                val bytes = input.readNBytes(4); require(bytes.size == 4)
                return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
            }
            require(number() == 0x8199e26d.toInt())
            var bytesRead = 4L
            fun document(length: Int): RawBsonDocument {
                require(length in 5..16_777_216)
                bytesRead += length; require(bytesRead <= 128L * 1024 * 1024)
                val bytes = ByteArray(length)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(length)
                val rest = input.readNBytes(length - 4); require(rest.size == length - 4)
                rest.copyInto(bytes, 4)
                return RawBsonDocument(bytes)
            }
            require(document(number()).getString("version").value == "0.1")
            val namespaces = linkedMapOf<String, MutableList<String>>()
            while (true) {
                val length = number(); if (length == -1) break
                val doc = document(length)
                val db = doc.getString("db").value; val collection = doc.getString("collection").value
                validateTransferDatabase(db)
                namespaces.getOrPut(db) { mutableListOf() }.add(collection)
            }
            if (namespaces.isEmpty()) throw InputError("This archive contains no collections or views to import.")
            return ArchiveInfo(gzip, namespaces)
        }
    } catch (e: InputError) { throw e }
    catch (_: Exception) { throw InputError("Choose a valid mongodump archive (.archive or .archive.gz). JSON and directory dumps are not supported by database import.") }
}

internal object DatabaseTools {
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    fun findDirectory(): String {
        val candidates = listOf(Path.of(System.getProperty("user.home"), ".mongodesk", "tools", "bin"), Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin")) +
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
        return candidates.firstOrNull { path -> listOf("mongodump", "mongorestore").all { Files.isExecutable(path.resolve(it + if (windows) ".exe" else "")) } }?.toString().orEmpty()
    }
    fun executable(directory: String, restore: Boolean): Path {
        val file = Path.of(directory).resolve((if (restore) "mongorestore" else "mongodump") + if (windows) ".exe" else "")
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) throw InputError("Select the MongoDB Database Tools bin folder containing mongodump and mongorestore.")
        return file.toAbsolutePath()
    }
}

internal fun secureTransferFile(path: Path, directory: Boolean = false) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
    else {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: throw InputError("Cannot restrict temporary credential file access on this filesystem.")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(java.util.EnumSet.allOf(AclEntryPermission::class.java))
        acl.acl = listOf(entry.build())
    }
}

internal fun redactDatabaseToolOutput(line: String, config: ConnectionConfig): String {
    var redacted = line.replace(Regex("mongodb(?:\\+srv)?://[^\\s\\\"']+", RegexOption.IGNORE_CASE), "[connection URI]")
    val secret = runCatching { ConnectionString(config.uri).credential?.password?.let(::String) }.getOrNull()
    listOfNotNull(secret, config.sshSecret.takeIf { it.isNotEmpty() }).filter { it.isNotBlank() }.forEach {
        redacted = redacted.replace(it, "[redacted]").replace(URLEncoder.encode(it, Charsets.UTF_8), "[redacted]")
    }
    return redacted.take(2000)
}

internal class DatabaseTransferService {
    internal suspend fun runTool(executable: Path, config: ConnectionConfig, uri: String, args: List<String>, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("mongodesk-transfer-")
        var process: Process? = null
        try {
            secureTransferFile(directory, true)
            val credentials = directory.resolve("connection.yml")
            Files.createFile(credentials); secureTransferFile(credentials)
            Files.writeString(credentials, Document("uri", uri).toJson()) // JSON is valid YAML; secrets stay out of process arguments.
            val child = ProcessBuilder(listOf(executable.toString(), "--config=$credentials") + args).redirectErrorStream(true).start()
            process = child; child.outputStream.close()
            val lines = java.util.ArrayDeque<String>()
            coroutineScope {
                val reader = launch(Dispatchers.IO) {
                    child.inputStream.bufferedReader().use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            val safe = redactDatabaseToolOutput(line, config)
                            if (lines.size >= 100) lines.removeFirst()
                            lines.addLast(safe)
                            withContext(Dispatchers.Main) { progress(safe) }
                        }
                    }
                }
                try {
                    while (!child.waitFor(150, TimeUnit.MILLISECONDS)) ensureActive()
                    reader.join(); ensureActive()
                    if (child.exitValue() != 0) throw InputError("MongoDB tool failed (exit ${child.exitValue()}). ${lines.toList().takeLast(5).joinToString("\n")}")
                } finally {
                    if (child.isAlive) { child.descendants().forEach { it.destroyForcibly() }; child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
                    reader.cancel()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: InputError) { throw e }
        catch (_: Exception) { throw InputError("Could not run MongoDB Database Tools. Check the tools folder and file permissions.") }
        finally { process?.let { if (it.isAlive) it.destroyForcibly() }; directory.toFile().deleteRecursively() }
    }
    suspend fun export(context: DatabaseTransferContext, source: DatabaseExportSource, destination: Path, tools: String, progress: (String) -> Unit) =
        export(context, source, destination, tools, DatabaseExportFormat.defaultFor(source), progress)
    suspend fun export(context: DatabaseTransferContext, source: DatabaseExportSource, destination: Path, tools: String, format: DatabaseExportFormat, progress: (String) -> Unit) {
        validateTransferDatabase(context.database)
        if (format !in DatabaseExportFormat.available(source)) throw InputError("Choose a supported export format for this source.")
        if (source == DatabaseExportSource.DATABASE && format != DatabaseExportFormat.ARCHIVE) {
            exportDatabaseFiles(context, destination, format, progress)
            return
        }
        val output = withContext(Dispatchers.IO) {
            Files.createTempFile(destination.toAbsolutePath().parent, ".mongodesk-export-", ".partial").also { secureTransferFile(it) }
        }
        try {
            if (format == DatabaseExportFormat.ARCHIVE) {
                val extra = if (source == DatabaseExportSource.COLLECTION) {
                    if (context.collection.isBlank()) throw InputError("Select a collection to export.")
                    listOf("--collection=${context.collection}")
                } else emptyList()
                runTool(DatabaseTools.executable(tools, false), context.session.config, databaseToolUri(context.session.config, context.session.transferTunnelPort),
                    listOf("--archive=$output", "--gzip", "--db=${context.database}") + extra, progress)
            } else {
                val flow: kotlinx.coroutines.flow.Flow<BsonDocument> = if (source == DatabaseExportSource.SELECTED) {
                    flowOf(context.selected ?: throw InputError("Select a document to export."))
                } else {
                    if (context.collection.isBlank()) throw InputError("Select a collection to export.")
                    if (source == DatabaseExportSource.QUERY && !context.queryLoaded) throw InputError("Run a query or pipeline before exporting its result.")
                    val collection = context.session.client.getDatabase(context.database).getCollection<BsonDocument>(context.collection)
                    if (source == DatabaseExportSource.QUERY && context.appliedPipeline != null) collection.aggregate<BsonDocument>(context.appliedPipeline).batchSize(500)
                    else collection.find(if (source == DatabaseExportSource.COLLECTION) BsonDocument() else parseFilter(context.appliedFilter)).sort(BsonDocument("_id", BsonInt32(1))).batchSize(500)
                }
                writeDocumentExport(flow, output, format, progress, context.collection)
            }
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                try { Files.move(output, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(output, destination, StandardCopyOption.REPLACE_EXISTING) }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(output) } }
    }
    suspend fun restore(context: DatabaseTransferContext, archive: Path, sourceDatabase: String, destinationDatabase: String,
        mode: DatabaseImportMode, tools: String, progress: (String) -> Unit) =
        restore(context, archive, sourceDatabase, destinationDatabase, mode, tools, progress, null, null)
    suspend fun restore(context: DatabaseTransferContext, archive: Path, sourceDatabase: String, destinationDatabase: String,
        mode: DatabaseImportMode, tools: String, progress: (String) -> Unit, sourceCollection: String?, destinationCollection: String?) {
        validateTransferDatabase(sourceDatabase); validateTransferDatabase(destinationDatabase)
        val info = withContext(Dispatchers.IO) { inspectDatabaseArchive(archive) }
        if (sourceDatabase !in info.collections) throw InputError("The archive does not contain the selected source database.")
        val existing = context.session.collections(destinationDatabase)
        if (sourceCollection != null) {
            listOf(sourceCollection, destinationCollection.orEmpty()).forEach { name ->
                if (name.isBlank() || name.any { it == '\u0000' || it in "*$\\" }) throw InputError("Choose a collection name without wildcards, dollar signs, backslashes, or null characters.")
            }
            if (sourceCollection !in info.collections[sourceDatabase].orEmpty()) throw InputError("The archive does not contain the selected source collection.")
        }
        if (mode == DatabaseImportMode.NEW && (if (sourceCollection == null) existing.isNotEmpty() else destinationCollection in existing)) throw InputError("The destination already has collections. Choose a new destination name or another import mode.")
        val sourceNamespace = "$sourceDatabase.${sourceCollection ?: "*"}"
        val destinationNamespace = "$destinationDatabase.${destinationCollection ?: "*"}"
        val args = listOf("--archive=$archive", "--nsInclude=$sourceNamespace", "--nsFrom=$sourceNamespace", "--nsTo=$destinationNamespace", "--stopOnError") +
            (if (info.gzip) listOf("--gzip") else emptyList()) + (if (mode == DatabaseImportMode.REPLACE) listOf("--drop") else emptyList())
        runTool(DatabaseTools.executable(tools, true), context.session.config, databaseToolUri(context.session.config, context.session.transferTunnelPort), args, progress)
    }
}
