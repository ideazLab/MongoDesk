package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import de.bwaldvogel.mongo.bson.Document
import de.bwaldvogel.mongo.exception.MongoServerError
import io.netty.channel.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ServerInfoTest {
    @Test fun requestsUseSelectedConnectionAndAdminDatabase() = runBlocking<Unit> {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        fun backend(host: String) = object : MemoryBackend() {
            override fun handleCommand(channel: Channel, database: String, command: String, query: Document): Document {
                if (command.equals("serverStatus", true) || command.equals("buildInfo", true)) {
                    requests.add(database to command)
                    assertEquals(10_000, query["maxTimeMS"])
                    return if (command.equals("serverStatus", true)) Document("ok", 1).append("host", host).append("uptime", 42).append("connections", Document("current", 3))
                        else Document("ok", 1).append("version", "7.0-test-$host").append("modules", listOf("fixture"))
                }
                return super.handleCommand(channel, database, command, query)
            }
        }
        val first = MongoServer(backend("first")); val firstAddress = first.bind()
        val second = MongoServer(backend("second")); val secondAddress = second.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${firstAddress.port}/application")).use { a ->
                MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${secondAddress.port}/another")).use { b ->
                    assertEquals("first", a.serverInfo(ServerInfoKind.STATUS).getString("host").value)
                    assertEquals("second", b.serverInfo(ServerInfoKind.STATUS).getString("host").value)
                    assertEquals("7.0-test-first", a.serverInfo(ServerInfoKind.BUILD).getString("version").value)
                    assertEquals("7.0-test-second", b.serverInfo(ServerInfoKind.BUILD).getString("version").value)
                }
            }
            assertEquals(4, requests.size)
            assertTrue(requests.all { it.first == "admin" })
        } finally { first.shutdownNow(); second.shutdownNow() }
    }
    @Test fun deniedStatusShowsMonitoringPrivilegeHelpButBuildStillWorks() = runBlocking<Unit> {
        val backend = object : MemoryBackend() {
            override fun handleCommand(channel: Channel, database: String, command: String, query: Document): Document {
                if (command.equals("serverStatus", true)) throw MongoServerError(13, "Unauthorized", "fixture-secret must not be exposed")
                if (command.equals("buildInfo", true)) return Document("ok", 1).append("version", "7.0-fixture")
                return super.handleCommand(channel, database, command, query)
            }
        }
        val server = MongoServer(backend); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                val error = assertFailsWith<InputError> { session.serverInfo(ServerInfoKind.STATUS) }
                assertTrue(error.message.orEmpty().contains("clusterMonitor"))
                assertFalse(error.message.orEmpty().contains("fixture-secret"))
                assertEquals("7.0-fixture", session.serverInfo(ServerInfoKind.BUILD).getString("version").value)
            }
        } finally { server.shutdownNow() }
    }
}
