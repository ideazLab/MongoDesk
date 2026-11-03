package com.ideazlab.mongodesk

import com.mongodb.ConnectionString
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import org.bson.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DatabaseTransferTest {
    @Test fun toolUriPreservesAuthenticationAndOptionsWithoutDatabaseRestriction() {
        val config = ConnectionConfig(uri = "mongodb://user:p%40ss@localhost:27017/original?retryWrites=false&authMechanism=SCRAM-SHA-256")
        val parsed = ConnectionString(databaseToolUri(config))
        assertNull(parsed.database)
        assertEquals("original", parsed.credential!!.source)
        assertEquals("p@ss", String(parsed.credential!!.password!!))
        assertTrue(databaseToolUri(config).contains("retryWrites=false"))
        val tunnel = ConnectionString(databaseToolUri(config, 34567))
        assertEquals(listOf("127.0.0.1:34567"), tunnel.hosts)
        assertEquals("original", tunnel.credential!!.source)
        assertFailsWith<InputError> { databaseToolUri(config.copy(uri = config.uri + "&tls=true"), 34567) }
    }
    @Test fun invalidNamesAndInvalidArchivesAreRejected() {
        listOf("", "a.b", "a*b", "a/b", "a b", "a\\b").forEach { name -> assertFailsWith<InputError> { validateTransferDatabase(name) } }
        val file = Files.createTempFile("invalid-mongodesk-archive", ".archive")
        try { Files.writeString(file, "not an archive"); assertFailsWith<InputError> { inspectDatabaseArchive(file) } }
        finally { Files.deleteIfExists(file) }
    }
    @Test fun processArgumentsDoNotContainCredentialsAndConfigIsRemovedAfterFailure() = runBlocking<Unit> {
        if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val dir = Files.createTempDirectory("mongodesk-tool-test")
        try {
            val report = dir.resolve("arguments")
            val script = dir.resolve("fake-tool")
            Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${report}'\necho 'mongodb://user:topsecret@localhost:27017/'\nexit 7\n")
            script.toFile().setExecutable(true)
            val config = ConnectionConfig(uri = "mongodb://user:topsecret@localhost:27017/")
            val error = assertFailsWith<InputError> { DatabaseTransferService().runTool(script, config, config.uri, listOf("--dryRun"), {}) }
            assertFalse(error.message.orEmpty().contains("topsecret"))
            val args = Files.readAllLines(report)
            assertFalse(args.any { "topsecret" in it || "mongodb://" in it })
            assertFalse(Files.exists(Path.of(args.first().substringAfter("--config="))))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun cancellingToolStopsItsProcessAndRemovesCredentials() = runBlocking<Unit> {
        if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val dir = Files.createTempDirectory("mongodesk-cancel-test")
        try {
            val report = dir.resolve("arguments"); val script = dir.resolve("fake-tool")
            Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$report'\nsleep 30\n")
            script.toFile().setExecutable(true)
            val config = ConnectionConfig()
            val started = System.nanoTime()
            assertFailsWith<TimeoutCancellationException> { withTimeout(400) { DatabaseTransferService().runTool(script, config, config.uri, emptyList(), {}) } }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            val args = Files.readAllLines(report)
            assertFalse(Files.exists(Path.of(args.first().substringAfter("--config="))))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun queryExportIncludesEveryPageAndPreservesBsonTypes() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val dir = Files.createTempDirectory("mongodesk-json-export")
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                session.client.getDatabase("fixture").getCollection<BsonDocument>("items").insertMany((1..125).map {
                    BsonDocument("_id", BsonInt32(it)).append("when", BsonDateTime(1742947200000)).append("long", BsonInt64(9_000_000_000))
                })
                val context = DatabaseTransferContext(session, "fixture", "items", queryLoaded = true)
                val file = dir.resolve("items.jsonl")
                DatabaseTransferService().export(context, DatabaseExportSource.QUERY, file, "", {})
                val rows = Files.readAllLines(file).map(BsonDocument::parse)
                assertEquals(125, rows.size); assertTrue(rows.first()["when"]!!.isDateTime); assertTrue(rows.first()["long"]!!.isInt64)
                val selected = dir.resolve("selected.jsonl")
                DatabaseTransferService().export(context.copy(selected = rows[70]), DatabaseExportSource.SELECTED, selected, "", {})
                assertEquals(listOf(rows[70]), Files.readAllLines(selected).map(BsonDocument::parse))
                val aggregated = dir.resolve("aggregate.jsonl")
                DatabaseTransferService().export(context.copy(appliedPipeline = listOf(BsonDocument.parse("{\$match:{_id:{\$gt:100}}}"))), DatabaseExportSource.QUERY, aggregated, "", {})
                assertEquals(25, Files.readAllLines(aggregated).size)
            }
        } finally { server.shutdownNow(); dir.toFile().deleteRecursively() }
    }
    @Test fun officialToolsRoundTripEntireDatabaseArchive() = runBlocking<Unit> {
        val tools = DatabaseTools.findDirectory()
        if (tools.isBlank()) return@runBlocking
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val dir = Files.createTempDirectory("mongodesk-archive-roundtrip")
        val progress = mutableListOf<String>()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val db = session.client.getDatabase("source")
                db.getCollection<BsonDocument>("items").insertMany((1..5).map { BsonDocument("_id", BsonInt32(it)).append("value", BsonString("value-$it")) })
                db.getCollection<BsonDocument>("other").insertOne(BsonDocument.parse("{_id:1,when:{\$date:'2025-03-26T00:00:00Z'}}"))
                db.runCommand(Document("create", "empty"))
                db.runCommand(Document("createIndexes", "items").append("indexes", listOf(Document("key", Document("value", 1)).append("name", "value_1"))))
                val context = DatabaseTransferContext(session, "source")
                val file = dir.resolve("database.archive.gz")
                val service = DatabaseTransferService()
                service.export(context, DatabaseExportSource.DATABASE, file, tools) { progress.add(it) }
                val info = inspectDatabaseArchive(file)
                assertTrue(info.gzip); assertEquals(setOf("items", "other", "empty"), info.collections.getValue("source").toSet())
                service.restore(context, file, "source", "restored", DatabaseImportMode.NEW, tools) { progress.add(it) }
                assertEquals(setOf("items", "other", "empty"), session.collections("restored").toSet())
                assertEquals(5, session.documents("restored", "items", "{}", 0).size)
                assertTrue(session.documents("restored", "other", "{}", 0).single()["when"]!!.isDateTime)
                val indexes = session.client.getDatabase("restored").runCommand(Document("listIndexes", "items"))
                    .get("cursor", Document::class.java).getList("firstBatch", Document::class.java)
                assertTrue(indexes.any { it.getString("name") == "value_1" })
                session.client.getDatabase("restored").getCollection<BsonDocument>("items").insertOne(BsonDocument.parse("{_id:99,value:'stale'}"))
                session.client.getDatabase("restored").getCollection<BsonDocument>("unrelated").insertOne(BsonDocument.parse("{_id:1}"))
                service.restore(context, file, "source", "restored", DatabaseImportMode.REPLACE, tools) { progress.add(it) }
                assertEquals(5, session.documents("restored", "items", "{}", 0).size)
                assertEquals(1, session.documents("restored", "unrelated", "{}", 0).size)
                assertFailsWith<InputError> { service.restore(context, file, "source", "restored", DatabaseImportMode.NEW, tools, {}) }
                service.restore(context, file, "source", "scoped", DatabaseImportMode.NEW, tools, {}, "items", "renamed")
                assertEquals(listOf("renamed"), session.collections("scoped"))
                assertEquals(5, session.documents("scoped", "renamed", "{}", 0).size)
                session.client.getDatabase("scoped").getCollection<BsonDocument>("untouched").insertOne(BsonDocument.parse("{_id:1}"))
                service.restore(context, file, "source", "scoped", DatabaseImportMode.REPLACE, tools, {}, "items", "renamed")
                assertEquals(setOf("renamed", "untouched"), session.collections("scoped").toSet())
                assertFailsWith<InputError> { service.restore(context, file, "source", "scoped", DatabaseImportMode.NEW, tools, {}, "items", "renamed") }
                assertFailsWith<InputError> { service.restore(context, file, "source", "scoped", DatabaseImportMode.REPLACE, tools, {}, "*", "renamed") }

            }
        } catch (e: Exception) { throw AssertionError("Archive round-trip failed: ${e.message}\n${progress.takeLast(12).joinToString("\n")}", e) }
        finally { server.shutdownNow(); dir.toFile().deleteRecursively() }
    }
}
