package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.bson.*
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Path

internal enum class DatabaseExportFormat(val label: String, val extension: String) {
    ARCHIVE("MongoDB archive (gzip)", ".archive.gz"), JSON("JSON (array)", ".json"), JSON_LINES("JSON Lines", ".jsonl"), CSV("CSV", ".csv"), EXCEL("Excel (.xlsx)", ".xlsx"), SQL("SQL INSERT script", ".sql"), COPY("Copy to another collection", "");
    companion object {
        fun available(source: DatabaseExportSource) = when (source) {
            DatabaseExportSource.DATABASE -> listOf(ARCHIVE, JSON, CSV, EXCEL, SQL)
            DatabaseExportSource.COLLECTION -> entries.toList()
            else -> listOf(JSON, JSON_LINES, CSV, EXCEL, SQL, COPY)
        }
        fun defaultFor(source: DatabaseExportSource) = if (source == DatabaseExportSource.DATABASE || source == DatabaseExportSource.COLLECTION) ARCHIVE else JSON_LINES
    }
}

internal fun csvExportCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
private val exportJsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()
internal fun csvExportValue(value: BsonValue?): String = when {
    value == null -> ""
    value.isDocument || value.isArray -> BsonDocument("v", value).toJson(exportJsonSettings).let { it.substring(it.indexOf(':') + 1, it.lastIndexOf('}')).trim() }
    else -> value.tableValue()
}

/** Streams JSON; CSV spools once to disk so fields introduced by later documents get columns too. */
internal suspend fun writeDocumentExport(documents: Flow<BsonDocument>, output: Path, format: DatabaseExportFormat,
    progress: (String) -> Unit, tableName: String = "documents") = withContext(Dispatchers.IO) {
    var count = 0L
    suspend fun report() { if (count % 500 == 0L) withContext(Dispatchers.Main) { progress("Exported $count documents…") } }
    if (format in listOf(DatabaseExportFormat.CSV, DatabaseExportFormat.EXCEL, DatabaseExportFormat.SQL)) {
        val spool = Files.createTempFile(output.parent, ".mongodesk-csv-", ".partial")
        try {
            secureTransferFile(spool)
            val columns = linkedSetOf<String>()
            Files.newBufferedWriter(spool).use { writer ->
                documents.collect { doc ->
                    ensureActive(); columns.addAll(doc.keys)
                    writer.write(doc.toJson(exportJsonSettings)); writer.newLine(); count++; report()
                }
            }
            withContext(Dispatchers.Main) { progress("Writing $count CSV rows with ${columns.size} columns…") }
            when (format) {
                DatabaseExportFormat.EXCEL -> writeExcelExport(spool, output, columns.toList(), count)
                DatabaseExportFormat.SQL -> Files.newBufferedWriter(output).use { writer ->
                    val table = sqlIdentifier(tableName)
                    writer.write("-- MongoDesk export: top-level fields stored as TEXT; objects/arrays use Extended JSON.\n")
                    if (columns.isNotEmpty()) {
                        writer.write("CREATE TABLE $table (${columns.joinToString(", ") { sqlIdentifier(it) + " TEXT" }});\n")
                        val prefix = "INSERT INTO $table (${columns.joinToString(", ") { sqlIdentifier(it) }}) VALUES ("
                        Files.newBufferedReader(spool).use { reader ->
                            while (true) {
                                ensureActive(); val doc = reader.readLine()?.let(BsonDocument::parse) ?: break
                                writer.write(prefix + columns.joinToString(", ") { key ->
                                    val value = doc[key]
                                    if (value == null || value.isNull) "NULL" else {
                                        val text = csvExportValue(value)
                                        if ('\u0000' in text) throw InputError("SQL text cannot contain null characters. Use JSON to preserve this value.")
                                        "'" + text.replace("'", "''") + "'"
                                    }
                                } + ");\n")
                            }
                        }
                    }
                }
                else -> Files.newBufferedWriter(output).use { writer ->
                    if (count > 0) {
                        writer.write(columns.joinToString(",") { csvExportCell(it) }); writer.write("\r\n")
                        Files.newBufferedReader(spool).use { reader ->
                            while (true) {
                                ensureActive(); val doc = reader.readLine()?.let(BsonDocument::parse) ?: break
                                writer.write(columns.joinToString(",") { csvExportCell(csvExportValue(doc[it])) }); writer.write("\r\n")
                            }
                        }
                    }
                }
            }
        } finally { Files.deleteIfExists(spool) }
    } else {
        require(format == DatabaseExportFormat.JSON || format == DatabaseExportFormat.JSON_LINES)
        Files.newBufferedWriter(output).use { writer ->
            if (format == DatabaseExportFormat.JSON) writer.write("[")
            documents.collect { doc ->
                ensureActive()
                if (format == DatabaseExportFormat.JSON && count > 0) writer.write(",")
                writer.write(doc.toJson(exportJsonSettings))
                if (format == DatabaseExportFormat.JSON_LINES) writer.newLine()
                count++; report()
            }
            if (format == DatabaseExportFormat.JSON) { writer.write("]"); writer.newLine() }
        }
    }
    withContext(Dispatchers.Main) { progress("Exported $count documents.") }
}

internal fun sqlIdentifier(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

/** Inline string cells prevent spreadsheet formula execution and preserve long numeric identifiers. */
private suspend fun writeExcelExport(spool: Path, output: Path, columns: List<String>, count: Long) {
    if (columns.size > 16384 || count > 1048575) throw InputError("Excel supports at most 16,384 columns and 1,048,575 document rows per sheet. Use CSV or JSON for this export.")
    fun xml(value: String): String {
        if (value.length > 32767) throw InputError("An Excel cell exceeds 32,767 characters. Use JSON or CSV to preserve the full value.")
        if (value.any { it.code < 32 && it !in "\t\n\r" }) throw InputError("An Excel cell contains unsupported control characters. Use JSON to preserve the value.")
        // OOXML interprets _xHHHH_ sequences; protect literal occurrences.
        return value.replace(Regex("_x[0-9a-fA-F]{4}_")) { "_x005F_" + it.value.substring(1) }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;")
    }
    java.util.zip.ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        fun entry(name: String, body: String) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Documents" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        zip.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        writer.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        fun row(values: List<String>) { writer.write("<row>"); values.forEach { writer.write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(it)}</t></is></c>") }; writer.write("</row>") }
        if (columns.isNotEmpty()) row(columns)
        Files.newBufferedReader(spool).use { reader ->
            while (true) { currentCoroutineContext().ensureActive(); val doc = reader.readLine()?.let(BsonDocument::parse) ?: break; row(columns.map { csvExportValue(doc[it]) }) }
        }
        writer.write("</sheetData></worksheet>"); writer.flush(); zip.closeEntry()
    }
}
