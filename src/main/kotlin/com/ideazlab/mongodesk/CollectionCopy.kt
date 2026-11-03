package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.bson.BsonDocument
import java.nio.file.Files

internal suspend fun copyDocuments(context: DatabaseTransferContext, source: DatabaseExportSource, destination: MongoSession,
    database: String, collection: String, append: Boolean, progress: (String) -> Unit): Long {
    validateTransferDatabase(database)
    if (source == DatabaseExportSource.DATABASE) throw InputError("Choose a collection or query result to copy.")
    if (collection.isBlank() || collection.contains('\u0000') || collection.contains('$') || collection.startsWith("system.")) throw InputError("Choose a valid destination collection name.")
    if (context.session.config.id == destination.config.id && context.database == database && context.collection == collection)
        throw InputError("Choose a different destination collection.")
    if (!destination.client.getDatabase(database).writeConcern.isAcknowledged) throw InputError("Copy requires acknowledged writes.")
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("mongodesk-copy-").also { secureTransferFile(it, true) } }
    try {
        // Complete the source snapshot before any destination writes, including aliases of the same server.
        val file = directory.resolve("documents.jsonl")
        DatabaseTransferService().export(context, source, file, "", DatabaseExportFormat.JSON_LINES, progress)
        val db = destination.client.getDatabase(database)
        val existing = db.listCollectionNames().toList()
        if (collection in existing && !append) throw InputError("The destination collection already exists. Choose another name or enable append.")
        if (collection !in existing) db.createCollection(collection)
        val target = db.getCollection<BsonDocument>(collection)
        var copied = 0L
        withContext(Dispatchers.IO) {
            Files.newBufferedReader(file).use { reader ->
                while (true) {
                    ensureActive()
                    val batch = ArrayList<BsonDocument>(500)
                    repeat(500) { reader.readLine()?.let { batch.add(BsonDocument.parse(it)) } }
                    if (batch.isEmpty()) break
                    target.insertMany(batch)
                    copied += batch.size
                    withContext(Dispatchers.Main) { progress("Copied $copied documents…") }
                }
            }
        }
        return copied
    } finally { withContext(NonCancellable + Dispatchers.IO) { directory.toFile().deleteRecursively() } }
}
