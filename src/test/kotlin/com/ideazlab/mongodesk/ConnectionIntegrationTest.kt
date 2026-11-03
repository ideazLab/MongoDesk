package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import java.nio.file.Files
import java.net.Socket
import kotlin.test.*

class ConnectionIntegrationTest {
    @Test fun directBrowseFilterAndPagination() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend())
        val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                session.client.getDatabase("fixture").getCollection<BsonDocument>("users").insertMany((1..105).map {
                    BsonDocument("_id", BsonInt32(it)).append("status", BsonString(if (it % 2 == 0) "active" else "inactive"))
                })
                assertTrue("fixture" in session.databases())
                assertEquals(listOf("users"), session.collections("fixture"))
                val first = session.documents("fixture", "users", "{}", 0)
                val second = session.documents("fixture", "users", "{}", 1)
                val last = session.documents("fixture", "users", "{}", 2)
                assertEquals(51, first.size) // One extra row determines whether Next is enabled.
                assertEquals(51, second.size)
                assertEquals(5, last.size)
                assertEquals(51, second.first()["_id"]!!.asInt32().value)
                assertTrue(session.documents("fixture", "users", """{"status":"active"}""", 0).all { it["status"]!!.asString().value == "active" })
                assertTrue(session.documents("fixture", "users", """{"missing":true}""", 0).isEmpty())
            }
        } finally { server.shutdownNow() }
    }

    @Test fun successfulPingDoesNotHideDatabaseDiscoveryFailure() = runBlocking<Unit> {
        val backend = object : MemoryBackend() {
            override fun handleCommand(channel: io.netty.channel.Channel, database: String, command: String,
                query: de.bwaldvogel.mongo.bson.Document): de.bwaldvogel.mongo.bson.Document {
                if (command.equals("listDatabases", ignoreCase = true))
                    throw de.bwaldvogel.mongo.exception.MongoServerError(13, "Unauthorized", "fixture secret must not be displayed")
                return super.handleCommand(channel, database, command, query)
            }
        }
        val server = MongoServer(backend)
        val address = server.bind()
        val config = ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")
        try {
            // Reproduce the old Test succeeding, while the complete connection workflow fails.
            MongoSession.connect(config).close()
            val error = assertFailsWith<DatabaseDiscoveryError> { MongoSession.openWorkspace(config) }
            val message = connectionError(error)
            assertTrue(message.contains("loading databases"))
            assertTrue(message.contains("code 13"))
            assertTrue(message.contains("Database field"))
            assertFalse(message.contains("fixture secret"))
            // An explicitly selected database bypasses global discovery and checks its collections.
            MongoSession.openWorkspace(config.copy(database = "fixture")).use {
                assertEquals(listOf("fixture"), it.databases)
                assertEquals(emptyList(), it.initialCollections)
            }
        } finally { server.shutdownNow() }
    }

    @Test fun workspaceDiscoveryRequestsOnlyAuthorizedDatabases() = runBlocking<Unit> {
        var authorizedOnly: Any? = null
        val backend = object : MemoryBackend() {
            override fun handleCommand(channel: io.netty.channel.Channel, database: String, command: String,
                query: de.bwaldvogel.mongo.bson.Document): de.bwaldvogel.mongo.bson.Document {
                if (command.equals("listDatabases", ignoreCase = true)) authorizedOnly = query["authorizedDatabases"]
                return super.handleCommand(channel, database, command, query)
            }
        }
        val server = MongoServer(backend)
        val address = server.bind()
        try {
            MongoSession.openWorkspace(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).close()
            assertEquals(true, authorizedOnly)
        } finally { server.shutdownNow() }
    }

    @Test fun sshPasswordTunnelBrowseAndCleanup() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend())
        val address = server.bind()
        val directory = Files.createTempDirectory("mongodesk-ssh-fixture")
        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        val provider = SimpleGeneratorHostKeyProvider(directory.resolve("host-key"))
        provider.algorithm = "RSA"
        sshd.keyPairProvider = provider
        sshd.setPasswordAuthenticator { username, password, _ -> username == "fixture" && password == "test-only-password" }
        sshd.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        try {
            sshd.start()
            val publicKey = provider.loadKeys(null).first().public
            val known = directory.resolve("known_hosts")
            Files.writeString(known, "[127.0.0.1]:${sshd.port} ${PublicKeyEntry.toString(publicKey)}\n")
            val config = ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}/fixture", ssh = true,
                sshHost = "127.0.0.1", sshPort = sshd.port.toString(), sshUser = "fixture", passwordAuth = true,
                sshSecret = "test-only-password", knownHosts = known.toString())
            MongoSession.connect(config).use { session ->
                assertEquals(listOf("fixture"), session.databases())
                session.client.getDatabase("fixture").getCollection<BsonDocument>("users").insertOne(BsonDocument("name", BsonString("Tunnel")))
                assertEquals(listOf("users"), session.collections("fixture"))
                assertEquals("Tunnel", session.documents("fixture", "users", "{}", 0).single()["name"]!!.asString().value)
            }
            val tunnel = SshTunnel.open(config, com.mongodb.ServerAddress("127.0.0.1", address.port))
            val port = tunnel.port
            tunnel.close()
            assertFails { Socket("127.0.0.1", port).close() }
            Files.writeString(known, "")
            assertFails { SshTunnel.open(config, com.mongodb.ServerAddress("127.0.0.1", address.port)) }
        } finally { sshd.stop(true); server.shutdownNow(); directory.toFile().deleteRecursively() }
    }
}
