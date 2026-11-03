package com.ideazlab.mongodesk

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class LocalSecretStoreTest {
    @Test fun encryptedCredentialsSurviveRestartWithoutPlaintextOnDisk() {
        val directory = Files.createTempDirectory("mongodesk-local-secrets-test")
        try {
            val secret = "mongodb://private-user:private-password@localhost"
            LocalSecretStore(directory).put("profile-1", secret)
            val restarted = LocalSecretStore(directory)
            assertEquals(secret, restarted.get("profile-1"))
            val ciphertext = Files.readAllBytes(directory.resolve("credentials/profile-1.enc"))
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains("private-password"))
            val original = ciphertext.toList()
            restarted.put("profile-1", secret)
            assertNotEquals(original, Files.readAllBytes(directory.resolve("credentials/profile-1.enc")).toList())
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("credentials.key"))))
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("credentials/profile-1.enc"))))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("credentials"))))
            }
            restarted.delete("profile-1")
            assertFailsWith<LocalCredentialMissing> { restarted.get("profile-1") }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun tamperingAndMissingKeyFailWithoutRegeneratingKey() {
        val directory = Files.createTempDirectory("mongodesk-local-integrity-test")
        try {
            val store = LocalSecretStore(directory)
            store.put("first", "first-secret"); store.put("second", "second-secret")
            val first = directory.resolve("credentials/first.enc")
            val bytes = Files.readAllBytes(first)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(first, bytes)
            assertFailsWith<InputError> { store.get("first") }
            Files.copy(directory.resolve("credentials/second.enc"), first, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFailsWith<InputError> { store.get("first") } // AAD prevents swapping profile files.
            Files.delete(directory.resolve("credentials.key"))
            assertFailsWith<InputError> { store.put("third", "new-secret") }
            assertFalse(Files.exists(directory.resolve("credentials.key")))
            assertFails { store.put("../escape", "secret") }
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun migrationVerifiesLocalCopiesAndRemovesLegacyEntries() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("mongodesk-credential-migration-test")
        val old = mutableMapOf<String, String>()
        val legacy = object : SecretStore {
            override fun get(id: String) = old.getValue(id)
            override fun put(id: String, secret: String) { old[id] = secret }
            override fun delete(id: String) { old.remove(id) }
        }
        try {
            val config = ConnectionConfig(name = "Existing", uri = "mongodb://user:password@localhost")
            ConnectionProfiles(directory, legacy).save(config)
            val profiles = ConnectionProfiles(directory, SessionSecretStore(LocalSecretStore(directory)))
            assertEquals(1, profiles.migrationCandidates().size)
            assertFailsWith<InputError> { profiles.delete(config.id) }
            assertEquals(1, migrateKeychainProfiles(profiles, legacy))
            assertTrue(old.isEmpty())
            assertTrue(profiles.migrationCandidates().isEmpty())
            assertEquals(config, ConnectionProfiles(directory, LocalSecretStore(directory)).load(config.id))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun failedLegacyCleanupRetainsLocalCopyAndCanBeRetried() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("mongodesk-migration-retry-test")
        val old = mutableMapOf<String, String>()
        var refuseDeletion = true
        val legacy = object : SecretStore {
            override fun get(id: String) = old.getValue(id)
            override fun put(id: String, secret: String) { old[id] = secret }
            override fun delete(id: String) { if (refuseDeletion) error("Denied"); old.remove(id) }
        }
        try {
            val config = ConnectionConfig()
            ConnectionProfiles(directory, legacy).save(config)
            val profiles = ConnectionProfiles(directory, LocalSecretStore(directory))
            assertFails { migrateKeychainProfiles(profiles, legacy) }
            assertEquals(config, profiles.read(config.id))
            assertTrue(old.containsKey(config.id))
            assertEquals(1, profiles.migrationCandidates().size)
            refuseDeletion = false
            assertEquals(1, migrateKeychainProfiles(profiles, legacy))
            assertTrue(profiles.migrationCandidates().isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }
}
