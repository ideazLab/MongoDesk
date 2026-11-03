package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.bson.BsonDocument
import org.bson.Document
import java.nio.file.*
import java.util.concurrent.TimeUnit

internal suspend fun serverExportDatabases(session: MongoSession): List<String> =
    session.client.listDatabases<BsonDocument>().nameOnly(true).authorizedDatabasesOnly(true).maxTime(10, TimeUnit.SECONDS)
        .toList().map { it.getString("name").value }.filter { it != "local" }.sorted()

internal suspend fun exportServer(session: MongoSession, destination: Path, format: DatabaseExportFormat, tools: String,
    progress: (String) -> Unit) {
    val target = destination.toAbsolutePath()
    val archive = format == DatabaseExportFormat.ARCHIVE
    val stage = withContext(Dispatchers.IO) {
        if (!archive && Files.exists(target)) throw InputError("Choose a new export folder.")
        if (archive) Files.createTempFile(target.parent, ".mongodesk-server-", ".partial").also { secureTransferFile(it) }
        else Files.createTempDirectory(target.parent, ".mongodesk-server-").also { secureTransferFile(it, true) }
    }
    try {
        if (archive) {
            DatabaseTransferService().runTool(DatabaseTools.executable(tools, false), session.config,
                databaseToolUri(session.config, session.transferTunnelPort), listOf("--archive=$stage", "--gzip"), progress)
        } else {
            val names = serverExportDatabases(session)
            val manifest = mutableListOf<Document>()
            names.forEachIndexed { index, db ->
                val folder = collectionExportFilename(index + 1, db, "")
                exportDatabaseFiles(DatabaseTransferContext(session, db), stage.resolve(folder), format) { progress("$db · $it") }
                manifest.add(Document("database", db).append("folder", folder))
            }
            withContext(Dispatchers.IO) {
                val file = stage.resolve("manifest.json")
                Files.writeString(file, Document("format", format.name).append("databases", manifest).toJson()); secureTransferFile(file)
            }
        }
        withContext(Dispatchers.IO) {
            ensureActive()
            if (archive) {
                try { Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING) }
            } else Files.move(stage, target)
        }
        withContext(Dispatchers.Main) { progress("Server export completed: $target") }
    } finally { withContext(NonCancellable + Dispatchers.IO) { if (archive) Files.deleteIfExists(stage) else stage.toFile().deleteRecursively() } }
}

internal suspend fun restoreServer(session: MongoSession, archive: Path, mode: DatabaseImportMode, tools: String,
    progress: (String) -> Unit) {
    val info = withContext(Dispatchers.IO) { inspectDatabaseArchive(archive) }
    if (mode == DatabaseImportMode.NEW) {
        // Check all namespaces before starting the restore, so a later conflict cannot cause a partial new-only import.
        info.collections.keys.forEach { db ->
            if (session.collections(db).isNotEmpty()) throw InputError("Database $db already contains collections. Choose merge/replace or a different server.")
        }
    }
    val args = listOf("--archive=$archive", "--stopOnError") + (if (info.gzip) listOf("--gzip") else emptyList()) +
        (if (mode == DatabaseImportMode.REPLACE) listOf("--drop") else emptyList())
    DatabaseTransferService().runTool(DatabaseTools.executable(tools, true), session.config,
        databaseToolUri(session.config, session.transferTunnelPort), args, progress)
}
