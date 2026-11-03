package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import org.bson.*
import java.nio.file.Files
import kotlin.test.*

class ServerTransferTest {
    @Test fun allDatabaseFilesIgnorePreferredDatabaseAndUseSeparateFolders() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val root = Files.createTempDirectory("mongodesk-server-files")
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}/first")).use { session ->
                for (db in listOf("first", "second", "local")) session.client.getDatabase(db).getCollection<BsonDocument>("items").insertOne(BsonDocument("db", BsonString(db)))
                assertEquals(listOf("first", "second"), serverExportDatabases(session))
                val folder = root.resolve("all")
                exportServer(session, folder, DatabaseExportFormat.JSON, "", {})
                val manifest = Document.parse(Files.readString(folder.resolve("manifest.json"))).getList("databases", Document::class.java)
                assertEquals(listOf("first", "second"), manifest.map { it.getString("database") })
                manifest.forEach { assertTrue(Files.exists(folder.resolve(it.getString("folder")).resolve("manifest.json"))) }
                assertFailsWith<InputError> { exportServer(session, folder, DatabaseExportFormat.JSON, "", {}) }
            }
        } finally { server.shutdownNow(); root.toFile().deleteRecursively() }
    }
    @Test fun serverArchiveRoundTripsAllDatabasesAndRejectsConflictsBeforeWriting() = runBlocking<Unit> {
        val tools = DatabaseTools.findDirectory(); if (tools.isBlank()) return@runBlocking
        val sourceServer = MongoServer(MemoryBackend()); val a = sourceServer.bind()
        val destServer = MongoServer(MemoryBackend()); val b = destServer.bind()
        val root = Files.createTempDirectory("mongodesk-server-archive")
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${a.port}/first")).use { source ->
                MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${b.port}")).use { target ->
                    for (db in listOf("first", "second")) source.client.getDatabase(db).getCollection<BsonDocument>("items").insertOne(BsonDocument("_id", BsonInt32(1)))
                    val archive = root.resolve("all.archive.gz")
                    exportServer(source, archive, DatabaseExportFormat.ARCHIVE, tools, {})
                    assertEquals(setOf("first", "second"), inspectDatabaseArchive(archive).collections.keys)
                    target.client.getDatabase("second").getCollection<BsonDocument>("existing").insertOne(BsonDocument("_id", BsonInt32(5)))
                    assertFailsWith<InputError> { restoreServer(target, archive, DatabaseImportMode.NEW, tools, {}) }
                    assertTrue(target.collections("first").isEmpty())
                    restoreServer(target, archive, DatabaseImportMode.MERGE, tools, {})
                    for (db in listOf("first", "second")) assertEquals(1, target.documents(db, "items", "{}", 0).size)
                    target.client.getDatabase("first").getCollection<BsonDocument>("items").insertOne(BsonDocument("_id", BsonInt32(2)))
                    restoreServer(target, archive, DatabaseImportMode.REPLACE, tools, {})
                    assertEquals(1, target.documents("first", "items", "{}", 0).size)
                    assertEquals(1, target.documents("second", "existing", "{}", 0).size)
                }
            }
        } finally { sourceServer.shutdownNow(); destServer.shutdownNow(); root.toFile().deleteRecursively() }
    }
}
