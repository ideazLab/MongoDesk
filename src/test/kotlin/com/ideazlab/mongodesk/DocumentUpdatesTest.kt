package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.bson.*
import kotlin.test.*

class DocumentUpdatesTest {
    @Test fun validatesIdAndShowsAddedRemovedAndChangedFields() {
        val original = BsonDocument.parse("""{"_id":{"${'$'}oid":"507f1f77bcf86cd799439011"},"name":"Before","removed":true}""")
        val replacement = parseEditedDocument(original, """{"_id":{"${'$'}oid":"507f1f77bcf86cd799439011"},"name":"After","added":42}""")
        assertEquals(setOf(DocumentFieldChange("name", "Changed"), DocumentFieldChange("removed", "Removed"), DocumentFieldChange("added", "Added")), documentChanges(original, replacement).toSet())
        listOf("invalid", "[]", "{}", "{\"_id\":\"507f1f77bcf86cd799439011\"}", "{\"\$set\":{\"name\":\"After\"}}").forEach {
            assertFailsWith<InputError> { parseEditedDocument(original, it) }
        }
    }
    @Test fun updatePreservesBsonTypesAndAppliesAdditionsRemovalsAndNestedChanges() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val collection = session.client.getDatabase("fixture").getCollection<BsonDocument>("docs")
                val original = BsonDocument.parse("""{"_id":{"${'$'}oid":"507f1f77bcf86cd799439011"},"large":{"${'$'}numberLong":"9007199254740993"},"date":{"${'$'}date":{"${'$'}numberLong":"1700000000000"}},"removed":true,"nested":{"name":"old"}}""")
                collection.insertOne(original)
                val edited = BsonDocument.parse(original.pretty()).apply {
                    remove("removed"); put("added", BsonString("new")); put("nested", BsonDocument("name", BsonString("updated")))
                }
                session.replaceDocument("fixture", "docs", original, edited)
                val stored = collection.find().first()
                assertEquals(edited, stored)
                assertEquals(BsonType.INT64, stored["large"]!!.bsonType)
                assertEquals(9007199254740993L, stored["large"]!!.asInt64().value)
                assertEquals(BsonType.DATE_TIME, stored["date"]!!.bsonType)
                assertEquals(original["_id"], stored["_id"])
            }
        } finally { server.shutdownNow() }
    }
    @Test fun concurrentAddedFieldPreventsOverwriteAndDeletedDocumentIsNotUpserted() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val collection = session.client.getDatabase("fixture").getCollection<BsonDocument>("docs")
                val original = BsonDocument("_id", BsonInt32(1)).append("name", BsonString("original"))
                val edited = BsonDocument.parse(original.pretty()).append("name", BsonString("edited"))
                collection.insertOne(original)
                collection.updateOne(BsonDocument("_id", BsonInt32(1)), BsonDocument("\$set", BsonDocument("external", BsonBoolean.TRUE)))
                assertFailsWith<DocumentConflictError> { session.replaceDocument("fixture", "docs", original, edited) }
                val stored = collection.find().first()
                assertTrue(stored["external"]!!.asBoolean().value)
                assertEquals("original", stored["name"]!!.asString().value)
                collection.deleteOne(BsonDocument("_id", BsonInt32(1)))
                assertFailsWith<DocumentConflictError> { session.replaceDocument("fixture", "docs", original, edited) }
                assertEquals(0L, collection.countDocuments())
            }
        } finally { server.shutdownNow() }
    }
    @Test fun unacknowledgedWritesAreRejected() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}/?w=0")).use { session ->
                val original = BsonDocument("_id", BsonInt32(1))
                val edited = BsonDocument("_id", BsonInt32(1)).append("x", BsonInt32(2))
                assertFailsWith<InputError> { session.replaceDocument("fixture", "docs", original, edited) }
            }
        } finally { server.shutdownNow() }
    }
}
