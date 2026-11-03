package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import org.bson.BsonDocument
import kotlin.test.*

class CreateCollectionTest {
    @Test fun createsEmptyCollectionOnlyInSelectedDatabaseAndPreservesExistingData() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val existing = BsonDocument.parse("{_id:1,value:'keep'}")
                session.client.getDatabase("selected").getCollection<BsonDocument>("existing").insertOne(existing)
                session.createCollection("selected", "events.history")
                assertEquals(setOf("existing", "events.history"), session.collections("selected").toSet())
                assertTrue(session.documents("selected", "events.history", "{}", 0).isEmpty())
                assertEquals(existing, session.documents("selected", "existing", "{}", 0).single())
                assertFailsWith<InputError> { session.createCollection("selected", "existing") }
                assertFailsWith<InputError> { session.createCollection("selected", "system.bad") }
                assertTrue(session.collections("other").isEmpty())
                assertEquals(existing, session.documents("selected", "existing", "{}", 0).single())
            }
        } finally { server.shutdownNow() }
    }
}
