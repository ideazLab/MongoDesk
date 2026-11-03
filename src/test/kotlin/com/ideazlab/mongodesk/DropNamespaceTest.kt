package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument
import kotlin.test.*

class DropNamespaceTest {
    @Test fun typedConfirmationRequiredAndDropsOnlyTheSelectedNamespace() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val otherServer = MongoServer(MemoryBackend()); val otherAddress = otherServer.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${otherAddress.port}")).use { other ->
                    for (connection in listOf(session, other)) for (db in listOf("target", "unrelated")) for (coll in listOf("items", "keep"))
                        connection.client.getDatabase(db).getCollection<BsonDocument>(coll).insertOne(BsonDocument.parse("{_id:1}"))
                    val collection = DropNamespaceTarget(session, "target", "items")
                    assertFailsWith<InputError> { dropNamespace(collection, "items") }
                    assertFailsWith<InputError> { dropNamespace(collection, "target.items ") }
                    assertEquals(1, session.documents("target", "items", "{}", 0).size)
                    dropNamespace(collection, "target.items")
                    assertEquals(listOf("keep"), session.collections("target"))
                    assertEquals(1, session.documents("target", "keep", "{}", 0).size)
                    val database = DropNamespaceTarget(session, "target")
                    assertFailsWith<InputError> { dropNamespace(database, "TARGET") }
                    dropNamespace(database, "target")
                    assertTrue(session.collections("target").isEmpty())
                    assertEquals(setOf("items", "keep"), session.collections("unrelated").toSet())
                    assertEquals(setOf("items", "keep"), other.collections("target").toSet())
                }
            }
        } finally { server.shutdownNow(); otherServer.shutdownNow() }
    }
}
