package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.bson.*
import kotlin.test.*

class DocumentFieldMutationTest {
    private fun original() = BsonDocument.parse("""{_id:1,settings:{keep:true,remove:{child:42}},rows:[{name:'one'}],literal:{'a.b':{keep:7}},typed:{'${'$'}numberLong':'9007199254740993'}}""")
    @Test fun addsAndDeletesNestedFieldsWithoutChangingSiblingsOrTypes() {
        val before = original()
        val added = addEditedField(before, listOf("settings"), "new", "{\"\$date\":\"2025-03-26T00:00:00Z\"}")
        assertTrue(fieldValue(added, listOf("settings", "new")).isDateTime)
        assertEquals(before["rows"], added["rows"]); assertEquals(before["typed"], added["typed"])
        assertFalse(before.getDocument("settings").containsKey("new"))
        val removed = deleteEditedField(added, listOf("settings", "remove"))
        assertFalse(removed.getDocument("settings").containsKey("remove"))
        assertEquals(BsonBoolean.TRUE, fieldValue(removed, listOf("settings", "keep")))
        assertTrue(fieldValue(removed, listOf("settings", "new")).isDateTime)
        val inArray = addEditedField(removed, listOf("rows", "0"), "extra", "[1,2]")
        assertTrue(fieldValue(inArray, listOf("rows", "0", "extra")).isArray)
        assertEquals(removed, deleteEditedField(inArray, listOf("rows", "0", "extra")))
        val dotted = addEditedField(before, listOf("literal", "a.b"), "new.field", "null")
        assertEquals(BsonNull.VALUE, fieldValue(dotted, listOf("literal", "a.b", "new.field")))
        assertEquals(before, deleteEditedField(dotted, listOf("literal", "a.b", "new.field")))
        val root = addEditedField(before, emptyList(), "extra", "false")
        assertEquals(before, deleteEditedField(root, listOf("extra")))
    }
    @Test fun rejectsDuplicateNamesInvalidParentsAndImmutableIdChanges() {
        val before = original()
        assertFailsWith<InputError> { addEditedField(before, listOf("settings"), "keep", "false") }
        assertFailsWith<InputError> { addEditedField(before, emptyList(), "_id", "2") }
        assertFailsWith<InputError> { addEditedField(before, listOf("_id"), "nested", "2") }
        assertFailsWith<InputError> { addEditedField(before, listOf("rows"), "name", "2") }
        for (name in listOf("", "bad\u0000name", "\$set")) assertFailsWith<InputError> { addEditedField(before, emptyList(), name, "1") }
        assertFailsWith<InputError> { addEditedField(before, emptyList(), "new", "1,2") }
        for (path in listOf(emptyList(), listOf("_id"), listOf("_id", "x"), listOf("missing"), listOf("rows", "0")))
            assertFailsWith<InputError> { deleteEditedField(before, path) }
        assertEquals(original(), before)
    }
    @Test fun savesAddAndDeleteAndRejectsStaleSnapshots() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val coll = session.client.getDatabase("fixture").getCollection<BsonDocument>("items")
                val before = original().apply { remove("literal") }; coll.insertOne(before)
                val added = addEditedField(before, listOf("rows", "0"), "extra", "{value:42}")
                session.replaceDocument("fixture", "items", before, added)
                assertEquals(added, coll.find().first())
                val deleted = deleteEditedField(added, listOf("settings", "remove"))
                session.replaceDocument("fixture", "items", added, deleted)
                assertEquals(deleted, coll.find().first())
                assertFailsWith<DocumentConflictError> { session.replaceDocument("fixture", "items", before, deleteEditedField(before, listOf("typed"))) }
                assertEquals(deleted, coll.find().first())
            }
        } finally { server.shutdownNow() }
    }
}
