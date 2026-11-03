package com.ideazlab.mongodesk

import kotlin.test.*
import java.nio.file.Files
import org.bson.BsonType

class ConnectionsTest {
    @Test fun extendedJsonPreservesTypes() {
        val doc = parseFilter("""{"_id":{"${'$'}oid":"507f1f77bcf86cd799439011"},"n":{"${'$'}numberLong":"9007199254740993"}}""")
        assertEquals(BsonType.OBJECT_ID, doc["_id"]!!.bsonType)
        assertEquals(9007199254740993L, doc["n"]!!.asInt64().value)
    }
    @Test fun invalidFilterHasSafeMessage() {
        val error = assertFailsWith<IllegalArgumentException> { parseFilter("db.users.find()") }
        assertTrue(error.message!!.contains("Extended JSON"))
    }
    @Test fun tunnelOverridesEndpointAndPreservesAuthenticationAndTls() {
        val known = Files.createTempFile("known-hosts", ".test")
        try {
            val config = ConnectionConfig(uri = "mongodb://user:secret@db.internal:27018/shop?authSource=admin&tls=true", ssh = true,
                sshHost = "jump.internal", sshUser = "tester", passwordAuth = true, sshSecret = "ssh-secret", knownHosts = known.toString())
            val settings = mongoSettings(config, 40001)
            assertEquals("127.0.0.1", settings.clusterSettings.hosts.single().host)
            assertEquals(40001, settings.clusterSettings.hosts.single().port)
            assertEquals("user", settings.credential!!.userName)
            assertEquals("admin", settings.credential!!.source)
            assertTrue(settings.sslSettings.isEnabled)
            assertFalse(settings.sslSettings.isInvalidHostNameAllowed)
        } finally { Files.deleteIfExists(known) }
    }
    @Test fun secretsNeverEnterProfileIndex() {
        val directory = Files.createTempDirectory("mongodesk-profile-test")
        val memory = mutableMapOf<String, String>()
        val store = object : SecretStore {
            override fun put(id: String, secret: String) { memory[id] = secret }
            override fun get(id: String) = memory.getValue(id)
        }
        try {
            val profiles = ConnectionProfiles(directory, store)
            val config = ConnectionConfig(uri = "mongodb://user:supersecret@localhost:27017", sshSecret = "private-passphrase")
            profiles.save(config)
            val index = Files.readString(directory.resolve("connections.json"))
            assertFalse(index.contains("supersecret")); assertFalse(index.contains("mongodb")); assertFalse(index.contains("private-passphrase"))
            val restarted = ConnectionProfiles(directory, store)
            assertEquals(config, restarted.load(restarted.lastUsedId()!!))
            assertEquals(listOf(SavedConnection(config.id, config.name)), restarted.list())
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun keychainFailureDoesNotCreateIndex() {
        val directory = Files.createTempDirectory("mongodesk-keychain-test")
        try {
            val profiles = ConnectionProfiles(directory, object : SecretStore {
                override fun put(id: String, secret: String) { error("locked") }
                override fun get(id: String): String = error("locked")
            })
            assertFails { profiles.save(ConnectionConfig()) }
            assertFalse(Files.exists(directory.resolve("connections.json")))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun errorsDoNotRevealDriverMessages() {
        assertFalse(connectionError(RuntimeException("mongodb://user:secret@private-host")).contains("secret@"))
    }
}
