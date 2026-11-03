package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class StartupReconnectTest {
    @Test fun rememberedConnectionsSurviveTabChangesAndExplicitDisconnect() {
        val dir = Files.createTempDirectory("mongodesk-startup-test")
        try {
            val store = CollectionTabStore(dir)
            val tab = CollectionTabRef(connectionId = "first", database = "db", collection = "items")
            store.save(listOf(tab), listOf("first", "second"))
            assertEquals(listOf("first", "second"), CollectionTabStore(dir).readWorkspace().openConnectionIds)
            store.save(emptyList())
            assertEquals(listOf("first", "second"), store.readWorkspace().openConnectionIds)
            store.save(listOf(tab), listOf("second"))
            assertEquals(listOf("second"), store.readWorkspace().openConnectionIds)
            assertEquals(listOf(tab), store.read())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun legacyTabsUpgradeToRememberedConnections() {
        val dir = Files.createTempDirectory("mongodesk-startup-test")
        try {
            Files.writeString(dir.resolve("workspace.json"), """{"version":1,"tabs":[{"id":"tab","connectionId":"first","database":"db","collection":"items"}]}""")
            assertEquals(listOf("first"), CollectionTabStore(dir).readWorkspace().openConnectionIds)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun failuresAreIsolatedAndConnectionsStartConcurrently() = runBlocking<Unit> {
        val otherStarted = CompletableDeferred<Unit>()
        val successful = mutableListOf<String>()
        val failed = mutableListOf<String>()
        withTimeout(3000) {
            reconnectSavedConnections(listOf("slow", "good", "broken", "good"), connect = { id ->
                when (id) {
                    "slow" -> { otherStarted.await(); successful.add(id) }
                    "good" -> { successful.add(id); otherStarted.complete(Unit) }
                    else -> throw InputError("Unavailable")
                }
            }, onFailure = { id, _ -> failed.add(id) })
        }
        assertEquals(setOf("slow", "good"), successful.toSet())
        assertEquals(2, successful.size); assertEquals(listOf("broken"), failed)
    }
    @Test fun cancellationClosesStartupWorkPromptly() = runBlocking<Unit> {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { reconnectSavedConnections(listOf("blocked"), { awaitCancellation() }, { _, _ -> fail("Cancellation must propagate") }) }
        }
    }
}
