package com.ideazlab.mongodesk

import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class SavedConnectionsTest {
    private class MemorySecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun put(id: String, secret: String) { values[id] = secret }
        override fun get(id: String) = values.getValue(id)
        override fun delete(id: String) { values.remove(id) }
    }

    @Test fun startupRestoresLastUsedDetailsAndKeepsNamedProfiles() {
        val directory = Files.createTempDirectory("mongodesk-persistence-test")
        val secrets = MemorySecrets()
        try {
            val first = ConnectionConfig(name = "Development", uri = "mongodb://user:secret@localhost/?authSource=admin",
                sshHost = "jump.private", sshUser = "developer", keyFile = "/private/key", sshSecret = "key-passphrase")
            val second = ConnectionConfig(name = "Reporting", database = "reports")
            ConnectionProfiles(directory, secrets).apply { save(first); save(second) }
            val restarted = ConnectionProfiles(directory, secrets)
            assertEquals(second.id, restarted.lastUsedId())
            assertEquals(listOf("Development", "Reporting"), restarted.list().map { it.name })
            assertEquals(first, restarted.load(first.id))
            assertEquals(first.id, ConnectionProfiles(directory, secrets).lastUsedId())
            val disk = Files.readString(directory.resolve("connections.json"))
            listOf("mongodb://", "secret@", "jump.private", "developer", "/private/key", "key-passphrase").forEach {
                assertFalse(disk.contains(it), "Connection settings leaked into metadata")
            }
            restarted.save(first.copy(name = "Development renamed"))
            assertEquals(2, restarted.ids().size)
            assertEquals("Development renamed", ConnectionProfiles(directory, secrets).list().first().name)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun saveAsNewKeepsOriginalAndAddsIndependentProfilesAcrossRestarts() {
        val directory = Files.createTempDirectory("mongodesk-multiple-profiles-test")
        val secrets = MemorySecrets()
        try {
            val profiles = ConnectionProfiles(directory, secrets)
            val original = ConnectionConfig(name = "Development", uri = "mongodb://localhost:27017")
            profiles.save(original)
            val second = profiles.saveAsNew(original.copy(uri = "mongodb://localhost:27018"))
            val third = profiles.saveAsNew(original.copy(uri = "mongodb://localhost:27019"))
            val restarted = ConnectionProfiles(directory, secrets)
            assertEquals(3, restarted.list().size)
            assertEquals(3, restarted.ids().toSet().size)
            assertEquals(listOf("Development", "Development copy", "Development copy 2"), restarted.list().map { it.name })
            assertEquals(original, restarted.load(original.id))
            assertEquals("mongodb://localhost:27018", restarted.load(second.id).uri)
            assertEquals("mongodb://localhost:27019", restarted.load(third.id).uri)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun deletionRemovesOnlySelectedProfileAndLastDeletionStaysEmptyAfterRestart() {
        val directory = Files.createTempDirectory("mongodesk-delete-test")
        val secrets = MemorySecrets()
        try {
            val profiles = ConnectionProfiles(directory, secrets)
            val first = ConnectionConfig(name = "Keep")
            val second = ConnectionConfig(name = "Delete")
            profiles.save(first); profiles.save(second)
            profiles.delete(second.id)
            val restarted = ConnectionProfiles(directory, secrets)
            assertEquals(listOf(first.id), restarted.ids())
            assertEquals(first.id, restarted.lastUsedId())
            assertFalse(secrets.values.containsKey(second.id))
            assertEquals(first, restarted.load(first.id))
            // A leftover legacy index must never resurrect deleted profiles.
            Files.write(directory.resolve("profiles"), listOf(first.id, second.id))
            restarted.delete(first.id)
            assertTrue(ConnectionProfiles(directory, secrets).list().isEmpty())
            assertNull(ConnectionProfiles(directory, secrets).lastUsedId())
            assertTrue(secrets.values.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun keychainDeletionFailureLeavesSavedProfilesUnchanged() {
        val directory = Files.createTempDirectory("mongodesk-delete-failure-test")
        val secrets = MemorySecrets()
        try {
            val config = ConnectionConfig()
            ConnectionProfiles(directory, secrets).save(config)
            val failing = object : SecretStore {
                override fun put(id: String, secret: String) { secrets.put(id, secret) }
                override fun get(id: String) = secrets.get(id)
                override fun delete(id: String) { error("Keychain locked") }
            }
            assertFails { ConnectionProfiles(directory, failing).delete(config.id) }
            assertEquals(config, ConnectionProfiles(directory, secrets).load(config.id))
            assertEquals(listOf(config.id), ConnectionProfiles(directory, secrets).ids())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun oldUuidIndexMigratesWithoutLosingProfiles() {
        val directory = Files.createTempDirectory("mongodesk-migration-test")
        val secrets = MemorySecrets()
        try {
            val first = ConnectionConfig(name = "Existing connection")
            val secondId = UUID.randomUUID().toString()
            secrets.put(first.id, first.encode())
            Files.write(directory.resolve("profiles"), listOf(first.id, secondId))
            val profiles = ConnectionProfiles(directory, secrets)
            assertEquals(first, profiles.load(first.id))
            assertEquals(listOf(first.id, secondId), profiles.ids())
            assertEquals("Existing connection", profiles.list().first().name)
            assertTrue(Files.exists(directory.resolve("connections.json")))
        } finally { directory.toFile().deleteRecursively() }
    }
}
