package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*
import org.bson.BsonDocument

class CollectionTabsTest {
    @Test fun openTabsSurviveRestartAndClosedTabsStayClosed() {
        val directory = Files.createTempDirectory("mongodesk-tabs-test")
        try {
            val first = CollectionTabRef(connectionId = "server-a", database = "db", collection = "items")
            val second = CollectionTabRef(connectionId = "server-b", database = "db", collection = "items")
            val store = CollectionTabStore(directory)
            store.save(listOf(first, second))
            assertEquals(listOf(first, second), CollectionTabStore(directory).read())
            assertFalse(first.sameCollection(second))
            store.save(listOf(second))
            assertEquals(listOf(second), CollectionTabStore(directory).read())
            store.save(emptyList())
            assertEquals(emptyList(), CollectionTabStore(directory).read())
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun duplicateCollectionLocationsRestoreOnce() {
        val directory = Files.createTempDirectory("mongodesk-tabs-test")
        try {
            val first = CollectionTabRef(connectionId = "a", database = "db", collection = "items")
            CollectionTabStore(directory).save(listOf(first, first.copy(id = "other")))
            assertEquals(listOf(first), CollectionTabStore(directory).read())
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun malformedMetadataIsNotOverwrittenDuringRestore() {
        val directory = Files.createTempDirectory("mongodesk-tabs-test")
        try {
            val file = directory.resolve("workspace.json")
            Files.writeString(file, "{broken")
            assertFailsWith<InputError> { CollectionTabStore(directory).read() }
            assertEquals("{broken", Files.readString(file))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun tabsHaveIndependentDraftsResultsAndSelections() {
        val first = CollectionTabState(CollectionTabRef(connectionId = "a", database = "db", collection = "one"))
        val second = CollectionTabState(CollectionTabRef(connectionId = "b", database = "db", collection = "two"))
        first.filter.value = "{x:1}"; first.mode.value = "Aggregation"; first.documents.value = listOf(BsonDocument.parse("{x:1}")); first.selected.value = 0
        first.stages.value = listOf(PipelineStage("\$limit", "2"))
        second.documents.value = listOf(BsonDocument.parse("{y:2}")); second.loaded.value = true
        assertEquals("{}", second.filter.value); assertEquals("Documents", second.mode.value)
        assertEquals("\$match", second.stages.value.single().operator)
        first.invalidateResults()
        assertEquals(1, second.documents.value.size); assertTrue(second.loaded.value)
        assertEquals("{x:1}", first.filter.value); assertEquals("\$limit", first.stages.value.single().operator)
    }
}

class SimultaneousConnectionsTest {
    @Test fun twoConnectionsRemainIndependentWhenOneDisconnects() = kotlinx.coroutines.runBlocking<Unit> {
        val firstServer = de.bwaldvogel.mongo.MongoServer(de.bwaldvogel.mongo.backend.memory.MemoryBackend())
        val secondServer = de.bwaldvogel.mongo.MongoServer(de.bwaldvogel.mongo.backend.memory.MemoryBackend())
        val firstAddress = firstServer.bind(); val secondAddress = secondServer.bind()
        var first: ConnectedWorkspace? = null
        var second: ConnectedWorkspace? = null
        try {
            first = MongoSession.openWorkspace(ConnectionConfig(name = "First", uri = "mongodb://127.0.0.1:${firstAddress.port}", database = "shared"))
            second = MongoSession.openWorkspace(ConnectionConfig(name = "Second", uri = "mongodb://127.0.0.1:${secondAddress.port}", database = "shared"))
            first.session.client.getDatabase("shared").getCollection<BsonDocument>("items").insertOne(BsonDocument.parse("{source:'first'}"))
            second.session.client.getDatabase("shared").getCollection<BsonDocument>("items").insertOne(BsonDocument.parse("{source:'second'}"))
            val open = mutableMapOf(first.session.config.id to OpenConnection(first), second.session.config.id to OpenConnection(second))
            assertEquals(2, open.size)
            assertEquals("first", open.getValue(first.session.config.id).session.documents("shared", "items", "{}", 0).single()["source"]!!.asString().value)
            assertEquals("second", open.getValue(second.session.config.id).session.documents("shared", "items", "{}", 0).single()["source"]!!.asString().value)
            open.getValue(first.session.config.id).expanded = false
            assertTrue(open.getValue(second.session.config.id).expanded)
            open.remove(first.session.config.id)!!.workspace.close()
            assertEquals("second", open.getValue(second.session.config.id).session.documents("shared", "items", "{}", 0).single()["source"]!!.asString().value)
        } finally { first?.close(); second?.close(); firstServer.shutdownNow(); secondServer.shutdownNow() }
    }
}
