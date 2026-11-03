package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.bson.*
import kotlin.test.*

class DocumentFieldEditorTest {
    private fun original() = BsonDocument.parse("""{"_id":1,"a.b":{"0":[{"name":"old","keep":true}]},"a":{"b":"separate"},"typed":{"${'$'}numberLong":"9007199254740993"}}""")
    @Test fun nestedEditsUseSegmentsAndPreserveBsonAndUnrelatedValues() {
        val before = original()
        val path = listOf("a.b", "0", "0", "name")
        val after = replaceEditedField(before, path, "\"new\"")
        assertEquals(BsonString("new"), fieldValue(after, path))
        assertEquals(BsonString("old"), fieldValue(before, path))
        assertEquals(before["a"], after["a"])
        assertEquals(before["typed"], after["typed"])
        assertEquals(BsonBoolean.TRUE, fieldValue(after, listOf("a.b", "0", "0", "keep")))
        assertEquals(BsonInt64(9007199254740995L), replaceEditedField(before, listOf("typed"), "{\"\$numberLong\":\"9007199254740995\"}")["typed"])
        val objectEdit = replaceEditedField(before, listOf("a.b", "0", "0"), "{replacement:[1,2],date:{\$date:'2025-03-26T00:00:00Z'}}")
        assertTrue(fieldValue(objectEdit, listOf("a.b", "0", "0", "date")).isDateTime)
        assertEquals(BsonNull.VALUE, replaceEditedField(before, listOf("a"), "null")["a"])
        val paths = editableFieldPaths(before)
        assertTrue(path in paths); assertFalse(paths.any { it.first() == "_id" })
        assertTrue(listOf("a", "b") in paths)
    }
    @Test fun invalidValuesMissingPathsAndIdChangesAreRejected() {
        val before = original()
        for (json in listOf("", "nope", "1,2", "1,\"value\":2", "{bad:"))
            assertFailsWith<InputError> { replaceEditedField(before, listOf("typed"), json) }
        for (path in listOf(emptyList(), listOf("_id"), listOf("_id", "x"), listOf("missing"), listOf("a.b", "0", "999")))
            assertFailsWith<InputError> { replaceEditedField(before, path, "1") }
        assertEquals(before, original())
    }
    @Test fun savesNestedFieldAndRejectsConcurrentChanges() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val coll = session.client.getDatabase("fixture").getCollection<BsonDocument>("items")
                // The in-memory server enforces older replacement rules for dotted field names;
                // literal dotted paths are covered by the pure transformation test above.
                val before = original().apply { put("nested", remove("a.b")!!) }; coll.insertOne(before)
                val changed = replaceEditedField(before, listOf("a", "b"), "\"updated\"")
                session.replaceDocument("fixture", "items", before, changed)
                assertEquals(changed, coll.find().first())
                val stale = replaceEditedField(before, listOf("typed"), "42")
                assertFailsWith<DocumentConflictError> { session.replaceDocument("fixture", "items", before, stale) }
                assertEquals(changed, coll.find().first())
            }
        } finally { server.shutdownNow() }
    }
}
