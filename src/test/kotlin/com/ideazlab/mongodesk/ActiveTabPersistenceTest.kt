package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*

class ActiveTabPersistenceTest {
    @Test fun activeCollectionAndDashboardSurviveRestartAndViewUpdates() {
        val dir = Files.createTempDirectory("mongodesk-active-tab-test")
        try {
            val first = CollectionTabRef(connectionId = "a", database = "db", collection = "items", view = DocumentView.TREE)
            val second = CollectionTabRef(connectionId = "b", database = "db", collection = "items", view = DocumentView.JSON)
            val store = CollectionTabStore(dir)
            store.save(listOf(first, second), listOf("a", "b"), second.id)
            assertEquals(second.id, CollectionTabStore(dir).readWorkspace().activeTabId)
            store.save(listOf(first.copy(view = DocumentView.JSON), second))
            assertEquals(second.id, store.readWorkspace().activeTabId)
            store.save(listOf(first, second), listOf("a", "b"), null)
            assertNull(CollectionTabStore(dir).readWorkspace().activeTabId)
            assertEquals(2, store.readWorkspace().tabs.size)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun closedOrMissingActiveTabFallsBackToDashboard() {
        val dir = Files.createTempDirectory("mongodesk-active-tab-test")
        try {
            val tab = CollectionTabRef(connectionId = "a", database = "db", collection = "items")
            val store = CollectionTabStore(dir)
            store.save(listOf(tab), listOf("a"), "missing")
            assertNull(store.readWorkspace().activeTabId)
            store.save(listOf(tab), listOf("a"), tab.id)
            store.save(emptyList())
            assertNull(store.readWorkspace().activeTabId)
            Files.writeString(dir.resolve("workspace.json"), """{"version":1,"tabs":[],"activeTabId":"missing"}""")
            assertNull(store.readWorkspace().activeTabId)
        } finally { dir.toFile().deleteRecursively() }
    }
}
