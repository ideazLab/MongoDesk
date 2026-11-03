package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.bson.*
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ExtendedExportTest {
    @Test fun excelAndSqlPreserveValuesAndLateFields() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mongodesk-extra-export")
        try {
            val docs = arrayOf(BsonDocument("_id", BsonInt64(9007199254740993)).append("value", BsonString("=1+1 & <雪> O'Brien\nsecond line")),
                BsonDocument("_id", BsonInt32(2)).append("late", BsonBoolean(true)))
            val file = dir.resolve("export.xlsx")
            writeDocumentExport(flowOf(*docs), file, DatabaseExportFormat.EXCEL, {})
            ZipFile(file.toFile()).use { zip ->
                val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { parser.parse(it) } }
                val sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use { parser.parse(it) }
                assertEquals(3, sheet.getElementsByTagName("row").length)
                assertEquals(9, sheet.getElementsByTagName("c").length)
                assertEquals(0, sheet.getElementsByTagName("f").length)
                assertTrue(sheet.documentElement.textContent.contains("9007199254740993"))
                assertTrue(sheet.documentElement.textContent.contains("=1+1 & <雪> O'Brien\nsecond line"))
            }
            val sql = dir.resolve("export.sql")
            writeDocumentExport(flowOf(*docs), sql, DatabaseExportFormat.SQL, {}, "odd\"table")
            val text = Files.readString(sql)
            assertTrue(text.contains("CREATE TABLE \"odd\"\"table\"")); assertTrue(text.contains("O''Brien"))
            assertEquals(2, Regex("INSERT INTO").findAll(text).count()); assertTrue(text.contains("NULL"))
            assertEquals(2, Files.list(dir).use { it.count() }.toInt())
            assertFailsWith<InputError> { writeDocumentExport(flowOf(BsonDocument("value", BsonString("x".repeat(32768)))), file, DatabaseExportFormat.EXCEL, {}) }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun copyAcrossConnectionsIncludesAllPagesAndHonorsSource() = runBlocking<Unit> {
        val first = MongoServer(MemoryBackend()); val a = first.bind()
        val second = MongoServer(MemoryBackend()); val b = second.bind()
        try {
            MongoSession.connect(ConnectionConfig(id = "source", uri = "mongodb://127.0.0.1:${a.port}")).use { source ->
                MongoSession.connect(ConnectionConfig(id = "target", uri = "mongodb://127.0.0.1:${b.port}")).use { target ->
                    source.client.getDatabase("fixture").getCollection<BsonDocument>("items").insertMany((1..125).map {
                        BsonDocument("_id", BsonInt32(it)).append("date", BsonDateTime(1742947200000))
                    })
                    val context = DatabaseTransferContext(source, "fixture", "items", appliedFilter = "{_id:{\$gt:100}}", queryLoaded = true)
                    assertEquals(125, copyDocuments(context, DatabaseExportSource.COLLECTION, target, "result", "all", false, {}))
                    assertEquals(25, copyDocuments(context, DatabaseExportSource.QUERY, target, "result", "query", false, {}))
                    assertTrue(target.documents("result", "query", "{}", 0).first()["date"]!!.isDateTime)
                    assertFailsWith<InputError> { copyDocuments(context, DatabaseExportSource.COLLECTION, target, "result", "all", false, {}) }
                    assertFailsWith<InputError> { copyDocuments(context, DatabaseExportSource.COLLECTION, source, "fixture", "items", true, {}) }
                    val selected = context.copy(selected = BsonDocument("_id", BsonInt32(999)))
                    assertEquals(1, copyDocuments(selected, DatabaseExportSource.SELECTED, target, "result", "query", true, {}))
                    assertEquals(26, target.documents("result", "query", "{}", 0).size)
                }
            }
        } finally { first.shutdownNow(); second.shutdownNow() }
    }
}
