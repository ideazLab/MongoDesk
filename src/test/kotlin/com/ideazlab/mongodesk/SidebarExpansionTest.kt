package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*

class SidebarExpansionTest {
    @Test fun sidebarExpansionRestoresIndependentlyForEachConnection() {
        val dir = Files.createTempDirectory("mongodesk-sidebar-test")
        try {
            val store = CollectionTabStore(dir)
            val states = mapOf("first" to SidebarExpansion(false, setOf("shared", "other")), "second" to SidebarExpansion(true, setOf("shared")))
            store.save(emptyList(), listOf("first", "second"), null, states)
            assertEquals(states, CollectionTabStore(dir).readWorkspace().sidebar)
            store.save(emptyList())
            assertEquals(states, store.readWorkspace().sidebar)
            val changed = states + ("second" to SidebarExpansion(false, emptySet()))
            store.save(emptyList(), listOf("first", "second"), null, changed)
            assertEquals(changed, CollectionTabStore(dir).readWorkspace().sidebar)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun olderWorkspaceHasDefaultExpansionPreferences() {
        val dir = Files.createTempDirectory("mongodesk-sidebar-test")
        try {
            Files.writeString(dir.resolve("workspace.json"), """{"version":1,"tabs":[],"openConnectionIds":["first"]}""")
            assertTrue(CollectionTabStore(dir).readWorkspace().sidebar.isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }
}
