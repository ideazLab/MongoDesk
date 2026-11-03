package com.ideazlab.mongodesk

import com.mongodb.MongoCommandException
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterConnectionMode
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.github.javakeyring.Keyring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

class InputError(message: String) : IllegalArgumentException(message)
private inline fun validateField(condition: Boolean, message: () -> String) { if (!condition) throw InputError(message()) }

// The complete configuration is treated as secret, including the URI.
data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Local development",
    val uri: String = "mongodb://localhost:27017",
    val database: String = "",
    val ssh: Boolean = false,
    val sshHost: String = "",
    val sshPort: String = "22",
    val sshUser: String = "",
    val keyFile: String = "",
    val sshSecret: String = "",
    val passwordAuth: Boolean = false,
    val knownHosts: String = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString()
) {
    fun validate(): ConnectionString {
        validateField(name.isNotBlank()) { "Enter a connection name." }
        val parsed = try { ConnectionString(uri.trim()) } catch (_: Exception) {
            throw InputError("Enter a valid mongodb:// or mongodb+srv:// URI.")
        }
        if (ssh) {
            validateField(sshHost.isNotBlank() && sshUser.isNotBlank()) { "Enter the SSH host and username." }
            validateField(sshPort.toIntOrNull() in 1..65535) { "SSH port must be between 1 and 65535." }
            validateField(!parsed.isSrvProtocol && parsed.hosts.size == 1) { "SSH requires a single-host mongodb:// URI. SRV and multi-host tunnels are not yet supported." }
            validateField(parsed.requiredReplicaSetName == null) { "SSH currently supports a direct connection to one server; remove replicaSet from the URI." }
            validateField(passwordAuth || keyFile.isNotBlank()) { "Choose a private key file or use SSH password authentication." }
            validateField(!passwordAuth || sshSecret.isNotEmpty()) { "Enter the SSH password." }
            validateField(Files.isRegularFile(Path.of(knownHosts))) { "Select an existing known_hosts file containing the trusted SSH host." }
            if (!passwordAuth) validateField(Files.isRegularFile(Path.of(keyFile))) { "The SSH private key file does not exist." }
        }
        return parsed
    }
    fun encode(): String = Document(mapOf(
        "id" to id, "name" to name, "uri" to uri, "database" to database, "ssh" to ssh,
        "sshHost" to sshHost, "sshPort" to sshPort, "sshUser" to sshUser, "keyFile" to keyFile,
        "sshSecret" to sshSecret, "passwordAuth" to passwordAuth, "knownHosts" to knownHosts
    )).toJson()
    companion object {
        fun decode(json: String): ConnectionConfig {
            val d = Document.parse(json)
            return ConnectionConfig(d.getString("id"), d.getString("name"), d.getString("uri"), d.getString("database"),
                d.getBoolean("ssh"), d.getString("sshHost"), d.getString("sshPort"), d.getString("sshUser"),
                d.getString("keyFile"), d.getString("sshSecret"), d.getBoolean("passwordAuth"), d.getString("knownHosts"))
        }
    }
}

interface SecretStore {
    fun put(id: String, secret: String)
    fun get(id: String): String
    fun delete(id: String) { throw UnsupportedOperationException("Deletion is not supported by this store") }
}
// Legacy migration only; normal runtime storage is LocalSecretStore.
class OsSecretStore : SecretStore {
    override fun put(id: String, secret: String) { Keyring.create().setPassword("MongoDesk", id, secret) }
    override fun get(id: String): String = Keyring.create().getPassword("MongoDesk", id)
    override fun delete(id: String) { Keyring.create().deletePassword("MongoDesk", id) }
}

data class SavedConnection(val id: String, val name: String)

// The public index stores names, UUIDs and selection; complete profiles are encrypted in local credential files.
class ConnectionProfiles(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk"),
    private val secrets: SecretStore = SessionCredentials.store
) {
    fun localStore() = LocalSecretStore(directory)
    fun migrationCandidates(): List<SavedConnection> {
        val local = localStore()
        return list().filter {
            if (!local.contains(it.id)) {
                // Keep legacy cleanup discoverable even if details are manually re-entered.
                local.markPendingRemoval(it.id)
                true
            } else local.pendingRemoval(it.id)
        }
    }
    private val metadata: Path get() = directory.resolve("connections.json")
    private fun readMetadata(): Document? = if (Files.exists(metadata)) Document.parse(Files.readString(metadata)) else null
    fun list(): List<SavedConnection> {
        val stored = readMetadata()
        if (stored != null) return stored.getList("connections", Document::class.java).map {
            SavedConnection(it.getString("id"), it.getString("name"))
        }
        // Backwards-compatible with the original UUID-only index.
        val legacy = directory.resolve("profiles")
        return if (Files.exists(legacy)) Files.readAllLines(legacy)
            .filter { runCatching { UUID.fromString(it) }.isSuccess }
            .mapIndexed { index, id -> SavedConnection(id, "Saved connection ${index + 1}") } else emptyList()
    }
    fun ids(): List<String> = list().map { it.id }
    fun lastUsedId(): String? {
        val saved = list()
        val last = readMetadata()?.getString("lastUsedId")
        return saved.firstOrNull { it.id == last }?.id ?: saved.firstOrNull()?.id
    }
    private fun decode(id: String, secret: String): ConnectionConfig = ConnectionConfig.decode(secret).also { config ->
        require(config.id == id) { "Saved connection identity mismatch." }
    }
    fun cached(id: String): ConnectionConfig? = (secrets as? SessionSecretStore)?.peek(id)?.let { decode(id, it) }
    fun read(id: String): ConnectionConfig = decode(id, secrets.get(id))
    fun markUsed(config: ConnectionConfig) {
        require(ids().contains(config.id)) { "Saved connection no longer exists." }
        remember(config)
    }
    fun load(id: String): ConnectionConfig = read(id).also { markUsed(it) }
    fun save(config: ConnectionConfig) {
        config.validate()
        secrets.put(config.id, config.encode()) // Persist encrypted locally before remembering metadata.
        remember(config)
    }
    fun saveAsNew(config: ConnectionConfig): ConnectionConfig {
        val names = list().map { it.name.lowercase() }.toSet()
        var name = config.name
        var suffix = 1
        while (name.lowercase() in names) {
            name = "${config.name} copy${if (suffix == 1) "" else " $suffix"}"
            suffix++
        }
        return config.copy(id = UUID.randomUUID().toString(), name = name).also { save(it) }
    }
    fun delete(id: String) {
        if (localStore().pendingRemoval(id)) throw InputError("Finish the one-time Keychain move before deleting this legacy profile, so its old credential entry can be removed too.")
        val existing = list()
        require(existing.any { it.id == id }) { "Saved connection not found." }
        val remaining = existing.filterNot { it.id == id }
        val last = lastUsedId().takeIf { it != id } ?: remaining.firstOrNull()?.id
        // Prepare metadata first so filesystem errors do not remove a working credential.
        val backup = secrets.get(id)
        val temp = prepareMetadata(remaining, last)
        try {
            secrets.delete(id) // If credential deletion fails, metadata stays unchanged.
            try { commitMetadata(temp) }
            catch (e: Exception) { secrets.put(id, backup); throw e }
        } finally { Files.deleteIfExists(temp) }
    }
    private fun remember(config: ConnectionConfig) {
        val existing = list()
        val updated = if (existing.any { it.id == config.id }) existing.map {
            if (it.id == config.id) SavedConnection(config.id, config.name) else it
        } else existing + SavedConnection(config.id, config.name)
        val temp = prepareMetadata(updated, config.id)
        try { commitMetadata(temp) } finally { Files.deleteIfExists(temp) }
    }
    private fun prepareMetadata(connections: List<SavedConnection>, last: String?): Path {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "connections-", ".tmp")
        try {
            Files.writeString(temp, Document("lastUsedId", last).append("connections",
                connections.map { Document("id", it.id).append("name", it.name) }).toJson())
            return temp
        } catch (e: Exception) { Files.deleteIfExists(temp); throw e }
    }
    private fun commitMetadata(temp: Path) {
        try { Files.move(temp, metadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, metadata, StandardCopyOption.REPLACE_EXISTING) }
    }
}

class SshTunnel private constructor(
    private val ssh: SSHClient,
    private val socket: ServerSocket,
    private val forwarder: net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
) : AutoCloseable {
    val port: Int get() = socket.localPort
    override fun close() {
        runCatching { forwarder.close() }
        runCatching { socket.close() }
        runCatching { ssh.close() }
    }
    companion object {
        fun open(config: ConnectionConfig, target: ServerAddress): SshTunnel {
            val ssh = SSHClient()
            var socket: ServerSocket? = null
            try {
                ssh.connectTimeout = 10000
                ssh.timeout = 15000
                ssh.loadKnownHosts(Path.of(config.knownHosts).toFile())
                ssh.connect(config.sshHost.trim(), config.sshPort.toInt())
                if (config.passwordAuth) ssh.authPassword(config.sshUser, config.sshSecret)
                else {
                    val key = if (config.sshSecret.isBlank()) ssh.loadKeys(config.keyFile)
                        else ssh.loadKeys(config.keyFile, config.sshSecret)
                    ssh.authPublickey(config.sshUser, key)
                }
                socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
                val forwarder = ssh.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, target.host, target.port), socket)
                val tunnel = SshTunnel(ssh, socket, forwarder)
                Thread({ runCatching { forwarder.listen() } }, "mongodesk-ssh").apply { isDaemon = true; start() }
                return tunnel
            } catch (e: Exception) {
                runCatching { socket?.close() }; runCatching { ssh.close() }; throw e
            }
        }
    }
}

fun mongoSettings(config: ConnectionConfig, tunnelPort: Int? = null): MongoClientSettings {
    val parsed = config.validate()
    return MongoClientSettings.builder().applyConnectionString(parsed)
        .applicationName("MongoDesk")
        .applyToClusterSettings {
            it.serverSelectionTimeout(10, TimeUnit.SECONDS)
            if (tunnelPort != null) it.hosts(listOf(ServerAddress("127.0.0.1", tunnelPort))).mode(ClusterConnectionMode.SINGLE)
        }
        .applyToSocketSettings { it.connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS) }
        .build()
}

class MongoSession private constructor(val client: MongoClient, private val tunnel: SshTunnel?, val config: ConnectionConfig) : AutoCloseable {
    internal val transferTunnelPort: Int? get() = tunnel?.port
    val preferredDatabase: String get() = config.database.trim().ifEmpty { ConnectionString(config.uri.trim()).database.orEmpty() }
    suspend fun databases(): List<String> {
        // Explicit database supports accounts without listDatabases permission.
        if (preferredDatabase.isNotEmpty()) return listOf(preferredDatabase)
        return client.listDatabases<BsonDocument>().nameOnly(true).authorizedDatabasesOnly(true)
            .maxTime(10, TimeUnit.SECONDS).toList().map { it.getString("name").value }.sorted()
    }
    suspend fun collections(database: String) = client.getDatabase(database).listCollectionNames().toList().sorted()
    suspend fun documents(database: String, collection: String, filter: String, page: Int, pageSize: Int = PAGE_SIZE): List<BsonDocument> {
        val parsed = parseFilter(filter)
        require(page >= 0)
        require(pageSize in PAGE_SIZES)
        return client.getDatabase(database).getCollection<BsonDocument>(collection)
            .find(parsed).sort(BsonDocument("_id", BsonInt32(1))).skip(Math.multiplyExact(page, pageSize))
            .limit(pageSize + 1).maxTime(10, TimeUnit.SECONDS).toList()
    }
    suspend fun documentCount(database: String, collection: String, filter: String): Long =
        client.getDatabase(database).getCollection<BsonDocument>(collection).countDocuments(parseFilter(filter),
            com.mongodb.client.model.CountOptions().maxTime(10, TimeUnit.SECONDS))

    suspend fun insertDocument(database: String, collection: String, document: BsonDocument): BsonDocument {
        val validated = parseNewDocument(document.pretty())
        val target = client.getDatabase(database).getCollection<BsonDocument>(collection)
        if (!target.writeConcern.isAcknowledged) throw InputError("Adding documents requires acknowledged writes. Remove w=0 from the URI and reconnect.")
        try { target.insertOne(validated); return validated }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw DocumentInsertError(e) }
    }
    suspend fun deleteDocument(database: String, collection: String, original: BsonDocument) {
        val filter = documentSnapshotFilter(original)
        val target = client.getDatabase(database).getCollection<BsonDocument>(collection)
        if (!target.writeConcern.isAcknowledged) throw InputError("Deleting documents requires acknowledged writes. Remove w=0 from the URI and reconnect.")
        try {
            val result = target.deleteOne(filter, com.mongodb.client.model.DeleteOptions()
                .collation(com.mongodb.client.model.Collation.builder().locale("simple").build()))
            if (result.deletedCount != 1L) throw DocumentConflictError()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: DocumentConflictError) { throw e }
        catch (e: Exception) { throw DocumentDeleteError(e) }
    }
    suspend fun replaceDocument(database: String, collection: String, original: BsonDocument, replacement: BsonDocument) {
        val validated = parseEditedDocument(original, replacement.pretty())
        if (documentChanges(original, validated).isEmpty()) return
        val target = client.getDatabase(database).getCollection<BsonDocument>(collection)
        if (!target.writeConcern.isAcknowledged) throw InputError("Document updates require acknowledged writes. Remove w=0 from the connection URI and reconnect.")
        try {
            val result = target.replaceOne(documentSnapshotFilter(original), validated, com.mongodb.client.model.ReplaceOptions().upsert(false)
                .collation(com.mongodb.client.model.Collation.builder().locale("simple").build()))
            if (result.matchedCount != 1L) throw DocumentConflictError()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: DocumentConflictError) { throw e }
        catch (e: Exception) { throw DocumentUpdateError(e) }
    }
    suspend fun aggregate(database: String, collection: String, pipeline: List<BsonDocument>, page: Int, pageSize: Int = PAGE_SIZE): List<BsonDocument> {
        validatePipeline(pipeline)
        require(page >= 0)
        require(pageSize in PAGE_SIZES)
        val paged = pipeline + listOf(
            BsonDocument("\$skip", BsonInt32(Math.multiplyExact(page, pageSize))),
            BsonDocument("\$limit", BsonInt32(pageSize + 1))
        )
        try {
            return client.getDatabase(database).getCollection<BsonDocument>(collection)
                .aggregate<BsonDocument>(paged).batchSize(pageSize + 1).maxTime(30, TimeUnit.SECONDS).toList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw AggregationRunError(e) }
    }
    override fun close() { runCatching { client.close() }; tunnel?.close() }
    companion object {
        const val PAGE_SIZE = 50
        val PAGE_SIZES = listOf(25, 50, 100, 250, 500)
        // Test and Connect deliberately use this same path, including explorer initialization.
        suspend fun openWorkspace(config: ConnectionConfig, progress: (String) -> Unit = {}): ConnectedWorkspace {
            val session = connect(config, progress)
            try {
                progress("Loading accessible databases…")
                val names = try { session.databases() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { throw DatabaseDiscoveryError(e) }
                // A named database skips listDatabases; check collection access instead.
                val initialCollections = if (session.preferredDatabase.isNotEmpty()) {
                    progress("Loading collections…")
                    try { session.collections(session.preferredDatabase) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { throw CollectionDiscoveryError(e) }
                } else null
                return ConnectedWorkspace(session, names, initialCollections)
            } catch (e: Exception) { session.close(); throw e }
        }
        suspend fun connect(config: ConnectionConfig, progress: (String) -> Unit = {}): MongoSession {
            val pending = java.util.concurrent.atomic.AtomicReference<MongoSession?>()
            try { return withContext(Dispatchers.IO) {
            val parsed = config.validate()
            progress(if (config.ssh) "Opening SSH tunnel…" else "Connecting to MongoDB…")
            val tunnel = if (config.ssh) SshTunnel.open(config, ServerAddress(parsed.hosts.single())) else null
            var client: MongoClient? = null
            try {
                client = MongoClient.create(mongoSettings(config, tunnel?.port))
                val session = MongoSession(client, tunnel, config)
                pending.set(session)
                progress("Checking MongoDB connection…")
                client.getDatabase(session.preferredDatabase.ifEmpty { "admin" }).runCommand(Document("ping", 1))
                session
            } catch (e: Exception) { runCatching { client?.close() }; tunnel?.close(); throw e }
            } } catch (e: Exception) { pending.getAndSet(null)?.close(); throw e }
        }
    }
}

data class ConnectedWorkspace(val session: MongoSession, val databases: List<String>, val initialCollections: List<String>?) : AutoCloseable {
    override fun close() = session.close()
}
class DatabaseDiscoveryError(cause: Throwable) : Exception("Database discovery failed", cause)
class CollectionDiscoveryError(cause: Throwable) : Exception("Collection discovery failed", cause)

fun parseFilter(value: String): BsonDocument = try { BsonDocument.parse(value) }
    catch (_: Exception) { throw IllegalArgumentException("The filter must be a valid Extended JSON object, for example {\"status\":\"active\"}.") }
val bsonJson: JsonWriterSettings = JsonWriterSettings.builder().indent(true).outputMode(JsonMode.EXTENDED).build()
fun BsonDocument.pretty(): String = toJson(bsonJson)

// Driver/SSH exception text may contain credentials or topology details. Never display it raw.
fun connectionError(error: Throwable): String {
    if (error is LocalCredentialMissing) return "This profile needs a one-time move from Keychain. Open Connections and choose Move existing profiles from Keychain, or re-enter and save its details locally."
    if (error is DocumentInsertError || error is DocumentDeleteError) {
        val action = if (error is DocumentInsertError) "Insertion" else "Deletion"
        val cause = error.cause!!
        val write = generateSequence(cause) { it.cause }.filterIsInstance<com.mongodb.MongoWriteException>().firstOrNull()
        return if (write != null) when (write.error.code) {
            11000 -> "Insertion failed: the _id or another unique indexed value already exists."
            121 -> "Insertion failed: the document does not match the collection's validation rules."
            else -> "$action rejected by MongoDB (code ${write.error.code})."
        } else if (generateSequence(cause) { it.cause }.any { it is MongoCommandException }) "$action failed. " + connectionError(cause)
        else "$action could not be confirmed. Reload the collection before retrying; the server may have applied the operation."
    }
    if (error is DocumentConflictError) return "The document changed or was deleted after you loaded it. No change was applied. Cancel, refresh the collection, and review your changes against the latest document."
    if (error is DocumentUpdateError) {
        val cause = error.cause!!
        val write = generateSequence(cause) { it.cause }.filterIsInstance<com.mongodb.MongoWriteException>().firstOrNull()
        return if (write != null) when (write.error.code) {
            121 -> "MongoDB rejected the document because it does not match the collection's validation rules."
            11000 -> "MongoDB rejected the update because it would duplicate a unique indexed value."
            else -> "MongoDB rejected the document update (code ${write.error.code}). Check collection constraints."
        } else if (generateSequence(cause) { it.cause }.any { it is MongoCommandException }) "Document update failed. " + connectionError(cause)
        else "Could not confirm the write. MongoDB may have applied it; refresh the collection and inspect the document before retrying."
    }
    if (error is AggregationRunError) {
        val command = generateSequence(error.cause) { it.cause }.filterIsInstance<MongoCommandException>().firstOrNull()
        return if (command != null && command.errorCode !in setOf(13, 18))
            "Aggregation failed (MongoDB code ${command.errorCode}). Check stage names, field paths and expressions."
        else "Aggregation failed. " + connectionError(error.cause!!)
    }
    if (error is DatabaseDiscoveryError) return "MongoDB responded, but loading databases failed. " + connectionError(error.cause!!) +
        " If your account is restricted to one database, enter its name in the Database field."
    if (error is CollectionDiscoveryError) return "MongoDB responded, but loading collections for the selected database failed. " + connectionError(error.cause!!)
    val command = generateSequence(error) { it.cause }.filterIsInstance<MongoCommandException>().firstOrNull()
    if (command != null) return when (command.errorCode) {
        13 -> "Not authorized (MongoDB code 13). Check database permissions and include MongoDB credentials in the URI; SSH credentials authenticate only the tunnel."
        18 -> "MongoDB authentication failed (code 18). Check the URI credentials and authSource."
        else -> "MongoDB rejected the operation (code ${command.errorCode}). Check server compatibility and permissions."
    }
    if (error is InputError) return error.message ?: "Check the connection fields."
    if (error is IllegalArgumentException) return when {
        error.message?.startsWith("The filter must") == true -> error.message!!
        else -> "Check the connection fields, URI, SSH key and known_hosts file. SSH requires one mongodb:// host without replicaSet."
    }
    val chain = generateSequence(error) { it.cause }.map { it.javaClass.simpleName }.toList()
    return when {
        chain.any { it.contains("Security") || it.contains("Auth") } -> "Authentication failed. Check the MongoDB or SSH credentials and authentication database."
        chain.any { it.contains("Transport") || it.contains("HostKey") } -> "SSH failed. Verify the host key in known_hosts, server address and SSH credentials."
        chain.any { it.contains("SSL") || it.contains("Certificate") } -> "TLS verification failed. Check certificates and the server hostname."
        chain.any { it.contains("Timeout") || it.contains("Connect") } -> "Connection timed out or was refused. Check the host, port, network and TLS settings."
        else -> "The operation failed. Check connection settings and database permissions. No credentials were logged."
    }
}
