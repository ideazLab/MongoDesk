package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import org.bson.Document
import java.nio.file.Files
import java.nio.file.Path

/** Numbered names avoid case-insensitive collisions; bounded escaped names work on all desktop OSes. */
internal fun collectionExportFilename(index: Int, collection: String, extension: String): String {
    val safe = buildString {
        collection.toByteArray(Charsets.UTF_8).forEach { byte ->
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n == 45 || n == 95) append(n.toChar())
            else append("%%%02X".format(n))
        }
    }.take(160)
    return "${index.toString().padStart(4, '0')}-$safe$extension"
}

internal suspend fun exportDatabaseFiles(context: DatabaseTransferContext, destination: Path, format: DatabaseExportFormat,
    progress: (String) -> Unit) {
    if (format !in listOf(DatabaseExportFormat.JSON, DatabaseExportFormat.CSV, DatabaseExportFormat.EXCEL, DatabaseExportFormat.SQL))
        throw InputError("Choose JSON, CSV, Excel, or SQL for a database folder export.")
    val target = destination.toAbsolutePath()
    val stage = withContext(Dispatchers.IO) {
        if (Files.exists(target)) throw InputError("The export folder already exists. Choose a new output folder.")
        Files.createTempDirectory(target.parent, ".mongodesk-database-").also { secureTransferFile(it, true) }
    }
    try {
        val names = context.session.collections(context.database)
        val manifest = mutableListOf<Document>()
        val service = DatabaseTransferService()
        names.forEachIndexed { index, name ->
            currentCoroutineContext().ensureActive()
            val filename = collectionExportFilename(index + 1, name, format.extension)
            withContext(Dispatchers.Main) { progress("Collection ${index + 1} of ${names.size}: $name") }
            service.export(context.copy(collection = name), DatabaseExportSource.COLLECTION, stage.resolve(filename), "", format) {
                progress("${index + 1}/${names.size} · $name · $it")
            }
            manifest.add(Document("collection", name).append("file", filename))
        }
        withContext(Dispatchers.IO) {
            val metadata = stage.resolve("manifest.json")
            Files.writeString(metadata, Document("database", context.database).append("format", format.name)
                .append("collections", manifest).toJson())
            secureTransferFile(metadata)
            ensureActive()
            // Publish only a complete set; never merge into or replace an existing export folder.
            Files.move(stage, target)
        }
        withContext(Dispatchers.Main) { progress("Exported ${names.size} collections to $target") }
    } finally { withContext(NonCancellable + Dispatchers.IO) { stage.toFile().deleteRecursively() } }
}
