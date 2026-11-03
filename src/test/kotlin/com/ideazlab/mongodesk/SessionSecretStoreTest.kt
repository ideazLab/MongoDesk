package com.ideazlab.mongodesk

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SessionSecretStoreTest {
    private class Backing : SecretStore {
        val values = mutableMapOf<String, String>()
        val reads = AtomicInteger()
        var writes = 0
        var failRead = false
        var failWrite = false
        override fun get(id: String): String {
            reads.incrementAndGet()
            if (failRead) error("Locked")
            return values.getValue(id)
        }
        override fun put(id: String, secret: String) {
            writes++
            if (failWrite) error("Locked")
            values[id] = secret
        }
        override fun delete(id: String) { values.remove(id) }
    }
    @Test fun profileReadsAcrossRepositoriesReuseOneUnlockAndUnchangedSavesSkipKeychain() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mongodesk-session-cache-test")
        val backing = Backing()
        try {
            val config = ConnectionConfig(uri = "mongodb://user:private@localhost:27017")
            ConnectionProfiles(dir, backing).save(config)
            val cache = SessionSecretStore(backing)
            val connectProfiles = ConnectionProfiles(dir, cache)
            val editorProfiles = ConnectionProfiles(dir, cache)
            assertEquals(config, loadSavedProfile(connectProfiles, config.id))
            assertEquals(config, loadSavedProfile(editorProfiles, config.id))
            editorProfiles.save(config)
            assertEquals(1, backing.reads.get())
            assertEquals(1, backing.writes)
            assertEquals(config, editorProfiles.cached(config.id))
            cache.clear()
            assertNull(editorProfiles.cached(config.id))
            assertEquals(config, loadSavedProfile(connectProfiles, config.id))
            assertEquals(2, backing.reads.get())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun changedCredentialsReplaceCacheOnlyAfterSuccessfulPersistence() {
        val backing = Backing().apply { values["id"] = "old" }
        val cache = SessionSecretStore(backing)
        assertEquals("old", cache.get("id"))
        backing.failWrite = true
        assertFails { cache.put("id", "new") }
        assertEquals("old", cache.get("id"))
        backing.failWrite = false
        cache.put("id", "new")
        assertEquals("new", cache.get("id"))
        assertEquals("new", backing.values["id"])
        assertEquals(1, backing.reads.get())
    }
    @Test fun deletedProfilesCannotBeReadFromCache() {
        val backing = Backing().apply { values["id"] = "secret" }
        val cache = SessionSecretStore(backing)
        cache.get("id")
        cache.delete("id")
        assertNull(cache.peek("id"))
        assertFails { cache.get("id") }
    }
    @Test fun failedReadsAreNotCached() {
        val backing = Backing().apply { values["id"] = "secret"; failRead = true }
        val cache = SessionSecretStore(backing)
        assertFails { cache.get("id") }
        assertNull(cache.peek("id"))
        backing.failRead = false
        assertEquals("secret", cache.get("id"))
        assertEquals(2, backing.reads.get())
    }
    @Test fun clearingSessionPreventsAnInFlightReadFromRepopulatingCache() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cache = SessionSecretStore(object : SecretStore {
            override fun put(id: String, secret: String) {}
            override fun get(id: String): String { entered.countDown(); release.await(); return "secret" }
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<String> { cache.get("id") }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cache.clear()
            release.countDown()
            assertEquals("secret", pending.get())
            assertNull(cache.peek("id"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentReadsShareOneBackingReadAndFreshSessionStartsEmpty() {
        val backing = Backing().apply { values["id"] = "secret" }
        val cache = SessionSecretStore(backing)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..12).map { pool.submit<String> { cache.get("id") } }
            futures.forEach { assertEquals("secret", it.get()) }
            assertEquals(1, backing.reads.get())
            val nextSession = SessionSecretStore(backing)
            assertNull(nextSession.peek("id"))
            assertEquals("secret", nextSession.get("id"))
            assertEquals(2, backing.reads.get())
        } finally { pool.shutdownNow() }
    }
}
