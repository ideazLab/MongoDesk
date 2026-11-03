package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CreateDatabaseTest {
    @Test fun createsEmptyCollectionOnTheChosenConnectionAndRejectsExistingNames() = runBlocking<Unit> {
        val first = MongoServer(MemoryBackend()); val a = first.bind()
        val second = MongoServer(MemoryBackend()); val b = second.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${a.port}")).use { source ->
                MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${b.port}")).use { other ->
                    source.createDatabase("newdb", "initial")
                    assertTrue("newdb" in source.databases())
                    assertEquals(listOf("initial"), source.collections("newdb"))
                    assertTrue(source.documents("newdb", "initial", "{}", 0).isEmpty())
                    assertFalse("newdb" in other.databases())
                    assertFailsWith<InputError> { source.createDatabase("newdb", "another") }
                    assertFailsWith<InputError> { source.createDatabase("NEWDB", "another") }
                    assertEquals(listOf("initial"), source.collections("newdb"))
                }
            }
        } finally { first.shutdownNow(); second.shutdownNow() }
    }
    @Test fun validatesNamesBeforeSendingWrites() {
        listOf("", "bad db", "bad.db", "a/b").forEach { db -> assertFailsWith<InputError> { validateNewDatabase(db, "items") } }
        listOf("", "system.users", "bad\$name", ".bad", "bad.", "bad\u0000name", "x".repeat(240)).forEach { name -> assertFailsWith<InputError> { validateNewDatabase("app", name) } }
        validateNewDatabase("app", "events.history")
    }
}
