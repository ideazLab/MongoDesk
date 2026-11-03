package com.ideazlab.mongodesk

import java.nio.file.*
import java.nio.file.attribute.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LocalCredentialMissing : Exception("Connection details have not been moved to local storage")

/** AES-GCM storage with a local owner-only key. It never accesses the OS keychain. */
class LocalSecretStore(private val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk")) : SecretStore {
    private val credentials get() = directory.resolve("credentials")
    private val keyFile get() = directory.resolve("credentials.key")
    private fun file(id: String): Path {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid credential identifier" }
        return credentials.resolve("$id.enc")
    }
    fun contains(id: String) = Files.isRegularFile(file(id), LinkOption.NOFOLLOW_LINKS)
    private fun secure(path: Path, directory: Boolean = false) {
        if (Files.isSymbolicLink(path)) throw InputError("Local credential storage cannot use symbolic links.")
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        else {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw InputError("This filesystem cannot restrict local credentials to your account.")
            val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(java.util.EnumSet.allOf(AclEntryPermission::class.java))
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
            acl.acl = listOf(entry.build())
        }
    }
    private fun prepareDirectory() {
        Files.createDirectories(directory); secure(directory, true)
        Files.createDirectories(credentials); secure(credentials, true)
    }
    private fun key(create: Boolean): ByteArray = synchronized(keyLock) {
        if (Files.exists(keyFile)) {
            secure(keyFile)
            Files.readAllBytes(keyFile).also { if (it.size != 32) throw InputError("The local credential key is invalid. Restore it from your backup; do not replace it.") }
        } else {
            val existing = Files.isDirectory(credentials) && Files.list(credentials).use { stream -> stream.anyMatch { it.fileName.toString().endsWith(".enc") } }
            if (!create || existing) throw InputError("The local credential key is missing. Restore credentials.key from your backup.")
            val generated = ByteArray(32).also(random::nextBytes)
            Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            secure(keyFile)
            generated
        }
    }
    override fun get(id: String): String {
        val path = file(id)
        if (!contains(id)) throw LocalCredentialMissing()
        secure(path)
        val encoded = Files.readAllBytes(path)
        if (encoded.size < 29 || encoded[0] != 1.toByte()) throw InputError("The local connection file is invalid. Restore it from your backup.")
        val key = key(false)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(encoded.copyOfRange(13, encoded.size)).toString(Charsets.UTF_8)
        } catch (_: java.security.GeneralSecurityException) { throw InputError("Cannot decrypt the local connection file. Check that the credential files and credentials.key belong to the same backup.") }
        finally { key.fill(0) }
    }
    override fun put(id: String, secret: String) {
        val destination = file(id)
        prepareDirectory()
        val key = key(true)
        val temporary = Files.createTempFile(credentials, "credential-", ".tmp")
        try {
            secure(temporary)
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            Files.write(temporary, byteArrayOf(1) + iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
        } finally { key.fill(0); Files.deleteIfExists(temporary) }
    }
    override fun delete(id: String) { Files.delete(file(id)) }
    fun pendingRemoval(id: String): Boolean = Files.exists(credentials.resolve("${file(id).fileName}.keychain-cleanup"))
    fun markPendingRemoval(id: String) {
        prepareDirectory()
        val path = credentials.resolve("${file(id).fileName}.keychain-cleanup")
        Files.writeString(path, "Remove legacy keychain entry after successful local migration.\n"); secure(path)
    }
    fun clearPendingRemoval(id: String) { Files.deleteIfExists(credentials.resolve("${file(id).fileName}.keychain-cleanup")) }
    companion object {
        private val random = SecureRandom()
        private val keyLock = Any()
    }
}

suspend fun migrateKeychainProfiles(profiles: ConnectionProfiles, source: SecretStore = OsSecretStore()): Int {
    var moved = 0
    for (saved in profiles.migrationCandidates()) {
        val local = profiles.localStore()
        if (!local.contains(saved.id)) {
            val encoded = try { awaitCredentialRead { source.get(saved.id) } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw InputError("The one-time Keychain import timed out. Approve the MongoDesk Keychain prompt, then retry.") }
            val config = ConnectionConfig.decode(encoded)
            if (config.id != saved.id) throw InputError("The legacy connection identity does not match the saved profile.")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                local.markPendingRemoval(saved.id)
                local.put(saved.id, encoded)
                if (local.get(saved.id) != encoded) throw InputError("Local connection verification failed. The Keychain entry was retained.")
            }
        }
        if (ConnectionConfig.decode(local.get(saved.id)).id != saved.id) throw InputError("Local connection verification failed. The Keychain entry was retained.")
        // The verified local copy is available even if deleting the old entry is denied.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            source.delete(saved.id)
            local.clearPendingRemoval(saved.id)
        }
        moved++
    }
    return moved
}
