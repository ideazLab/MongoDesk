package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.bson.*
import kotlin.test.*

class DocumentCrudTest {
    @Test fun newDocumentValidationAndIdGeneration() {
        assertTrue(parseNewDocument("{}")["_id"]!!.isObjectId)
        assertEquals(BsonInt32(7), parseNewDocument("{\"_id\":7}")["_id"])
        listOf("bad", "[]", "{\"_id\":[]}", "{\"\$set\":{}}").forEach { assertFailsWith<InputError> { parseNewDocument(it) } }
    }
    @Test fun insertsGeneratedAndExplicitIdsPreservesTypesAndRejectsDuplicates() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val draft = parseNewDocument("""{"large":{"${'$'}numberLong":"9007199254740993"}}""")
                val inserted = session.insertDocument("fixture", "docs", draft)
                assertEquals(draft["_id"], inserted["_id"])
                val explicit = parseNewDocument("{\"_id\":42,\"name\":\"explicit\"}")
                session.insertDocument("fixture", "docs", explicit)
                assertEquals(2L, session.client.getDatabase("fixture").getCollection<BsonDocument>("docs").countDocuments())
                val error = assertFailsWith<DocumentInsertError> { session.insertDocument("fixture", "docs", explicit) }
                assertTrue(connectionError(error).contains("already exists"))
                val stored = session.client.getDatabase("fixture").getCollection<BsonDocument>("docs").find(BsonDocument("_id", draft["_id"]!!)).first()
                assertEquals(9007199254740993L, stored["large"]!!.asInt64().value)
            }
        } finally { server.shutdownNow() }
    }
    @Test fun deletesOnlySelectedSnapshotAndRejectsStaleOrMissingDocuments() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val first = session.insertDocument("fixture", "docs", parseNewDocument("{\"_id\":1}"))
                val second = session.insertDocument("fixture", "docs", parseNewDocument("{\"_id\":2}"))
                session.deleteDocument("fixture", "docs", first)
                val collection = session.client.getDatabase("fixture").getCollection<BsonDocument>("docs")
                assertEquals(second, collection.find().first())
                assertFailsWith<DocumentConflictError> { session.deleteDocument("fixture", "docs", first) }
                collection.updateOne(BsonDocument("_id", BsonInt32(2)), BsonDocument("\$set", BsonDocument("changed", BsonBoolean.TRUE)))
                assertFailsWith<DocumentConflictError> { session.deleteDocument("fixture", "docs", second) }
                assertEquals(1L, collection.countDocuments())
            }
        } finally { server.shutdownNow() }
    }
    @Test fun insertAndDeleteRejectUnacknowledgedWrites() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}/?w=0")).use { session ->
                val document = parseNewDocument("{\"_id\":1}")
                assertFailsWith<InputError> { session.insertDocument("fixture", "docs", document) }
                assertFailsWith<InputError> { session.deleteDocument("fixture", "docs", document) }
            }
        } finally { server.shutdownNow() }
    }
}
