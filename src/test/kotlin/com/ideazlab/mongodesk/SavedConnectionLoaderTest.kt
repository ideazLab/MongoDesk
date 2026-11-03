package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class SavedConnectionLoaderTest {
    @Test fun blockedNativeReadTimesOutWithoutHoldingCaller() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        try {
            withTimeout(2000) {
                assertFailsWith<TimeoutCancellationException> {
                    awaitCredentialRead(80) {
                        var done = false
                        while (!done) {
                            try { release.await(); done = true } catch (_: InterruptedException) { /* Simulate uninterruptible native consent. */ }
                        }
                        "unused credential"
                    }
                }
            }
        } finally { release.countDown() }
    }
    @Test fun savedConnectionReportsStagesAndLoadsWorkspaceWithoutEditor() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        val directory = Files.createTempDirectory("mongodesk-saved-load-test")
        val values = mutableMapOf<String, String>()
        val secrets = object : SecretStore {
            override fun put(id: String, secret: String) { values[id] = secret }
            override fun get(id: String) = values.getValue(id)
        }
        try {
            val profiles = ConnectionProfiles(directory, secrets)
            val config = ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")
            profiles.save(config)
            val stages = mutableListOf<String>()
            openSavedWorkspace(profiles, config.id, progress = { stages.add(it) }).use {
                assertEquals(config, it.session.config)
            }
            assertEquals("Reading encrypted local connection details…", stages.first())
            assertTrue(stages.contains("Checking MongoDB connection…"))
            assertEquals("Loading accessible databases…", stages.last())
        } finally { server.shutdownNow(); directory.toFile().deleteRecursively() }
    }
    @Test fun slowServerSetupHasABoundedFailure() = runBlocking<Unit> {
        val backend = object : MemoryBackend() {
            override fun handleCommand(channel: io.netty.channel.Channel, database: String, command: String,
                query: de.bwaldvogel.mongo.bson.Document): de.bwaldvogel.mongo.bson.Document {
                if (command.equals("ping", true)) Thread.sleep(500)
                return super.handleCommand(channel, database, command, query)
            }
        }
        val server = MongoServer(backend); val address = server.bind()
        val directory = Files.createTempDirectory("mongodesk-setup-timeout-test")
        val values = mutableMapOf<String, String>()
        try {
            val profiles = ConnectionProfiles(directory, object : SecretStore {
                override fun put(id: String, secret: String) { values[id] = secret }
                override fun get(id: String) = values.getValue(id)
            })
            val config = ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")
            profiles.save(config)
            withTimeout(3000) {
                val error = assertFailsWith<InputError> { openSavedWorkspace(profiles, config.id, timeoutMillis = 100) }
                assertTrue(error.message!!.contains("Connection setup"))
            }
        } finally { server.shutdownNow(); directory.toFile().deleteRecursively() }
    }
}
