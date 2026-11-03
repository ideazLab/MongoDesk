package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import org.bson.*
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.*

class DatabaseFileExportTest {
    @Test fun eachFormatExportsEveryCollectionToSeparateFiles() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val root = Files.createTempDirectory("mongodesk-database-files-test")
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val db = session.client.getDatabase("fixture")
                db.getCollection<BsonDocument>("items").insertMany((1..125).map { BsonDocument("_id", BsonInt32(it)) })
                db.getCollection<BsonDocument>("another").insertOne(BsonDocument("name", BsonString("other")))
                db.createCollection("empty")
                for (format in listOf(DatabaseExportFormat.JSON, DatabaseExportFormat.CSV, DatabaseExportFormat.EXCEL, DatabaseExportFormat.SQL)) {
                    assertTrue(format in DatabaseExportFormat.available(DatabaseExportSource.DATABASE))
                    val target = root.resolve(format.name)
                    DatabaseTransferService().export(DatabaseTransferContext(session, "fixture", "ignored", appliedFilter = "{_id:1}"), DatabaseExportSource.DATABASE, target, "", format, {})
                    val manifest = Document.parse(Files.readString(target.resolve("manifest.json"))).getList("collections", Document::class.java)
                    assertEquals(setOf("items", "another", "empty"), manifest.map { it.getString("collection") }.toSet())
                    assertEquals(4, Files.list(target).use { it.count() }.toInt())
                    val items = target.resolve(manifest.first { it.getString("collection") == "items" }.getString("file"))
                    when (format) {
                        DatabaseExportFormat.JSON -> assertEquals(125, BsonDocument.parse("{rows:${Files.readString(items)}}").getArray("rows").size)
                        DatabaseExportFormat.CSV -> assertEquals(126, Files.readAllLines(items).size)
                        DatabaseExportFormat.SQL -> { val sql = Files.readString(items); assertEquals(125, Regex("INSERT INTO").findAll(sql).count()); assertTrue(sql.contains("CREATE TABLE \"items\"")) }
                        DatabaseExportFormat.EXCEL -> ZipFile(items.toFile()).use { zip ->
                            val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().readText()
                            assertEquals(126, Regex("<row>").findAll(sheet).count())
                        }
                        else -> error("Unexpected format")
                    }
                    assertFailsWith<InputError> { DatabaseTransferService().export(DatabaseTransferContext(session, "fixture"), DatabaseExportSource.DATABASE, target, "", format, {}) }
                    assertTrue(Files.exists(items))
                }
                db.getCollection<BsonDocument>("badExcel").insertOne(BsonDocument("long", BsonString("x".repeat(32768))))
                val failed = root.resolve("failure")
                assertFailsWith<InputError> { DatabaseTransferService().export(DatabaseTransferContext(session, "fixture"), DatabaseExportSource.DATABASE, failed, "", DatabaseExportFormat.EXCEL, {}) }
                assertFalse(Files.exists(failed))
                assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".mongodesk") } })
            }
        } finally { server.shutdownNow(); root.toFile().deleteRecursively() }
    }
    @Test fun filenamesAreBoundedAndCannotEscapeTheFolderOrCollideByCase() {
        val one = collectionExportFilename(1, "../../CON:雪", ".json")
        assertFalse('/' in one); assertFalse('\\' in one); assertFalse(':' in one)
        assertNotEquals(collectionExportFilename(1, "Account", ".json").lowercase(), collectionExportFilename(2, "account", ".json").lowercase())
        assertTrue(collectionExportFilename(1, "雪".repeat(200), ".xlsx").length < 200)
    }
}
