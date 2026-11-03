package com.ideazlab.mongodesk

import org.bson.Document
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

data class SavedPipeline(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val stages: List<PipelineStage>, val rawJson: String, val jsonMode: Boolean,
    val database: String = "", val collection: String = ""
)

class PipelineLibrary(private val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk")) {
    private val file get() = directory.resolve("pipelines.json")
    private fun read(): Document = try {
        if (!Files.exists(file)) Document("version", 1).append("pipelines", emptyList<Document>())
        else Document.parse(Files.readString(file)).also {
            require(it.getInteger("version") == 1)
            require(it["pipelines"] is List<*>)
        }
    } catch (_: Exception) { throw InputError("Could not read the saved pipeline library. Check pipelines.json and file permissions; the file has not been overwritten.") }
    private fun records(document: Document): List<SavedPipeline> = try {
        document.getList("pipelines", Document::class.java).map { d ->
            SavedPipeline(d.getString("id"), d.getString("name"), d.getList("stages", Document::class.java).map {
                PipelineStage(it.getString("operator"), it.getString("body"), it.getBoolean("enabled"), it.getString("id"))
            }, d.getString("rawJson"), d.getBoolean("jsonMode"), d.getString("database").orEmpty(), d.getString("collection").orEmpty())
        }
    } catch (_: Exception) { throw InputError("The saved pipeline library is invalid. Back up pipelines.json before repairing it.") }
    fun list(): List<SavedPipeline> = records(read())
    fun last(): SavedPipeline? {
        val d = read(); val pipelines = records(d)
        return pipelines.firstOrNull { it.id == d.getString("lastUsedId") } ?: pipelines.firstOrNull()
    }
    fun load(id: String): SavedPipeline {
        val pipelines = list()
        val selected = pipelines.firstOrNull { it.id == id } ?: throw InputError("Saved pipeline not found.")
        write(pipelines, id)
        return selected
    }
    fun save(pipeline: SavedPipeline, asNew: Boolean = false): SavedPipeline {
        val name = pipeline.name.trim()
        if (name.isEmpty()) throw InputError("Give the pipeline a name before saving.")
        if (name.length > 120) throw InputError("Pipeline names must be at most 120 characters.")
        if (pipeline.jsonMode) parsePipeline(pipeline.rawJson) else compileStages(pipeline.stages)
        val pipelines = list()
        var saved = pipeline.copy(name = name)
        if (asNew) {
            val names = pipelines.map { it.name.lowercase() }.toSet()
            var label = name; var count = 1
            while (label.lowercase() in names) { label = "$name copy${if (count == 1) "" else " $count"}"; count++ }
            saved = saved.copy(id = UUID.randomUUID().toString(), name = label)
        }
        val updated = if (pipelines.any { it.id == saved.id }) pipelines.map { if (it.id == saved.id) saved else it } else pipelines + saved
        write(updated, saved.id)
        return saved
    }
    fun delete(id: String) {
        val d = read(); val existing = records(d)
        if (existing.none { it.id == id }) throw InputError("Saved pipeline not found.")
        val remaining = existing.filterNot { it.id == id }
        val last = d.getString("lastUsedId")?.takeIf { selected -> remaining.any { it.id == selected } } ?: remaining.firstOrNull()?.id
        write(remaining, last)
    }
    private fun write(pipelines: List<SavedPipeline>, last: String?) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "pipelines-", ".tmp")
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            val documents = pipelines.map { p ->
                Document("id", p.id).append("name", p.name).append("rawJson", p.rawJson).append("jsonMode", p.jsonMode)
                    .append("database", p.database).append("collection", p.collection).append("stages", p.stages.map {
                        Document("id", it.id).append("operator", it.operator).append("body", it.body).append("enabled", it.enabled)
                    })
            }
            Files.writeString(temp, Document("version", 1).append("lastUsedId", last).append("pipelines", documents).toJson())
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } catch (_: Exception) { throw InputError("Could not save the pipeline library. Check file permissions and free disk space.") }
        finally { Files.deleteIfExists(temp) }
    }
}
