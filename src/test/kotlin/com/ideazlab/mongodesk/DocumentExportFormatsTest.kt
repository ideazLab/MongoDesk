package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.bson.*
import java.nio.file.Files
import kotlin.test.*

class DocumentExportFormatsTest {
    private fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val cells = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c == '"') {
                if (quoted && index < text.length && text[index] == '"') { cell.append('"'); index++ }
                else quoted = !quoted
            } else if (!quoted && c == ',') { cells.add(cell.toString()); cell.clear() }
            else if (!quoted && c == '\r' && index < text.length && text[index] == '\n') {
                index++; cells.add(cell.toString()); cell.clear(); rows.add(cells.toList()); cells.clear()
            } else cell.append(c)
        }
        assertFalse(quoted)
        if (cell.isNotEmpty() || cells.isNotEmpty()) { cells.add(cell.toString()); rows.add(cells.toList()) }
        return rows
    }
    @Test fun csvQuotesSpecialCharactersAndIncludesLateFields() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("mongodesk-csv-test")
        try {
            val first = BsonDocument("_id", BsonInt32(1)).append("name", BsonString("München, \"quoted\"\nsecond line"))
                .append("nested", BsonDocument.parse("{count:2}")).append("tags", BsonArray(listOf(BsonString("a,b"), BsonInt32(2))))
            val second = BsonDocument("_id", BsonInt32(2)).append("late", BsonString("only later")).append("nil", BsonNull.VALUE)
            val file = directory.resolve("rows.csv")
            writeDocumentExport(flowOf(first, second), file, DatabaseExportFormat.CSV, {})
            val rows = readCsv(Files.readString(file))
            assertEquals(listOf("_id", "name", "nested", "tags", "late", "nil"), rows.first())
            assertEquals(3, rows.size); assertTrue(rows.all { it.size == 6 })
            assertEquals("München, \"quoted\"\nsecond line", rows[1][1])
            assertEquals(first["nested"], BsonDocument.parse(rows[1][2]))
            assertEquals(first["tags"], BsonDocument.parse("{value:${rows[1][3]}}")["value"])
            assertEquals("", rows[1][4]); assertEquals("only later", rows[2][4]); assertEquals("null", rows[2][5])
            assertEquals("", rows[2][1])
            assertEquals(listOf("rows.csv"), Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() })
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun jsonArrayAndCsvExportEntireCollectionAndFullQueryResults() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val directory = Files.createTempDirectory("mongodesk-formats-test")
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                session.client.getDatabase("fixture").getCollection<BsonDocument>("items").insertMany((1..125).map {
                    BsonDocument("_id", BsonInt32(it)).append("date", BsonDateTime(1742947200000)).append("large", BsonInt64(9_000_000_000))
                })
                val context = DatabaseTransferContext(session, "fixture", "items", appliedFilter = "{_id:{\$gt:100}}", queryLoaded = true)
                val service = DatabaseTransferService()
                val all = directory.resolve("all.json")
                service.export(context, DatabaseExportSource.COLLECTION, all, "", DatabaseExportFormat.JSON, {})
                val docs = BsonDocument.parse("{rows:${Files.readString(all)}}").getArray("rows")
                assertEquals(125, docs.size); assertTrue(docs.first().asDocument()["date"]!!.isDateTime); assertTrue(docs.first().asDocument()["large"]!!.isInt64)
                val filtered = directory.resolve("query.csv")
                service.export(context, DatabaseExportSource.QUERY, filtered, "", DatabaseExportFormat.CSV, {})
                val rows = readCsv(Files.readString(filtered))
                assertEquals(26, rows.size); assertEquals("101", rows[1][0]); assertEquals("125", rows.last()[0])
                val aggregated = directory.resolve("aggregation.json")
                service.export(context.copy(appliedPipeline = listOf(BsonDocument.parse("{\$match:{_id:{\$lte:75}}}"))), DatabaseExportSource.QUERY, aggregated, "", DatabaseExportFormat.JSON, {})
                assertEquals(75, BsonDocument.parse("{rows:${Files.readString(aggregated)}}").getArray("rows").size)
            }
        } finally { server.shutdownNow(); directory.toFile().deleteRecursively() }
    }
    @Test fun emptyJsonExportIsValidAndCsvCancellationRemovesSpool() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("mongodesk-export-empty-test")
        try {
            val json = directory.resolve("empty.json")
            writeDocumentExport(flowOf(), json, DatabaseExportFormat.JSON, {})
            assertTrue(BsonDocument.parse("{rows:${Files.readString(json)}}").getArray("rows").isEmpty())
            val csv = directory.resolve("cancel.csv")
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) { writeDocumentExport(flow { emit(BsonDocument.parse("{value:'pending'}")); awaitCancellation() }, csv, DatabaseExportFormat.CSV, {}) }
            }
            assertEquals(listOf("empty.json"), Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() })
        } finally { directory.toFile().deleteRecursively() }
    }
}
