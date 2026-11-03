package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*

class PipelineLibraryTest {
    @Test fun multiplePipelinesSurviveRestartWithDisabledStagesAndExactJson() {
        val dir = Files.createTempDirectory("mongodesk-pipelines-test")
        try {
            val library = PipelineLibrary(dir)
            val stages = listOf(PipelineStage("\$match", """{"n":{"${'$'}numberLong":"9007199254740993"}}"""),
                PipelineStage("\$project", "unfinished draft", enabled = false))
            val first = library.save(SavedPipeline(name = "Counts", stages = stages, rawJson = "[]", jsonMode = false, database = "shop", collection = "orders"))
            val json = """[ { "${'$'}group": { "_id": null, "n": { "${'$'}sum": 1 } } } ]"""
            val second = library.save(SavedPipeline(name = "Totals", stages = emptyList(), rawJson = json, jsonMode = true))
            val restarted = PipelineLibrary(dir)
            assertEquals(listOf("Counts", "Totals"), restarted.list().map { it.name })
            assertEquals(second, restarted.last())
            assertEquals(first, restarted.load(first.id))
            assertEquals(first, PipelineLibrary(dir).last())
            assertEquals(stages, restarted.load(first.id).stages)
            assertEquals(json, restarted.load(second.id).rawJson)
            val file = dir.resolve("pipelines.json")
            if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun saveAsNewUpdatesAndDeletionKeepIndependentRecords() {
        val dir = Files.createTempDirectory("mongodesk-pipeline-crud-test")
        try {
            val library = PipelineLibrary(dir)
            val first = library.save(SavedPipeline(name = "Report", stages = emptyList(), rawJson = "[]", jsonMode = false))
            val second = library.save(first, asNew = true)
            assertNotEquals(first.id, second.id)
            assertEquals("Report copy", second.name)
            library.save(first.copy(name = "Renamed"))
            assertEquals(2, library.list().size)
            library.delete(second.id)
            assertEquals(listOf("Renamed"), PipelineLibrary(dir).list().map { it.name })
            library.delete(first.id)
            assertTrue(PipelineLibrary(dir).list().isEmpty())
            assertNull(PipelineLibrary(dir).last())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun invalidDraftOrCorruptLibraryDoesNotOverwriteSavedPipelines() {
        val dir = Files.createTempDirectory("mongodesk-pipeline-validation-test")
        try {
            val library = PipelineLibrary(dir)
            val valid = library.save(SavedPipeline(name = "Good", stages = emptyList(), rawJson = "[]", jsonMode = false))
            val file = dir.resolve("pipelines.json")
            val original = Files.readString(file)
            assertFailsWith<InputError> { library.save(valid.copy(name = " ")) }
            assertFailsWith<InputError> { library.save(valid.copy(rawJson = "invalid", jsonMode = true)) }
            assertEquals(original, Files.readString(file))
            Files.writeString(file, "corrupt")
            assertFailsWith<InputError> { library.save(valid) }
            assertEquals("corrupt", Files.readString(file))
        } finally { dir.toFile().deleteRecursively() }
    }
}
