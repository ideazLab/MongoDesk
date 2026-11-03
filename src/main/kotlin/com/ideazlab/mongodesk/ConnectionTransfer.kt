package com.ideazlab.mongodesk

import org.bson.Document
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted backups. Never exports the local storage key. */
internal object ConnectionTransfer {
    private const val ITERATIONS = 210_000
    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun encode(configs: List<ConnectionConfig>, password: CharArray): String {
        if (password.size < 8) throw InputError("Use an export password of at least 8 characters.")
        require(configs.isNotEmpty())
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = derive(password, salt)
        val bytes = Document("connections", configs.map { Document.parse(it.encode()) }).toJson().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD("MongoDesk-connections-v1".toByteArray())
            val b64 = Base64.getEncoder()
            return Document("format", "MongoDesk-connections").append("version", 1)
                .append("salt", b64.encodeToString(salt)).append("iv", b64.encodeToString(iv))
                .append("data", b64.encodeToString(cipher.doFinal(bytes))).toJson()
        } finally { key.fill(0); bytes.fill(0) }
    }
    fun decode(json: String, password: CharArray): List<ConnectionConfig> {
        if (json.length > 8_000_000) throw InputError("Connection backup is too large.")
        try {
            val doc = Document.parse(json)
            require(doc.getString("format") == "MongoDesk-connections" && doc.getInteger("version") == 1)
            val b64 = Base64.getDecoder()
            val salt = b64.decode(doc.getString("salt")); val iv = b64.decode(doc.getString("iv"))
            require(salt.size == 16 && iv.size == 12)
            val key = derive(password, salt)
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD("MongoDesk-connections-v1".toByteArray())
                cipher.doFinal(b64.decode(doc.getString("data")))
            } finally { key.fill(0) }
            return try {
                val entries = Document.parse(plain.toString(Charsets.UTF_8)).getList("connections", Document::class.java)
                require(entries.size in 1..1000)
                entries.map { ConnectionConfig.decode(it.toJson()).also { config -> config.validate() } }
            } finally { plain.fill(0) }
        } catch (_: Exception) {
            throw InputError("Cannot import backup. Check the password and MongoDesk backup file. SSH profiles also require valid local key and known_hosts paths.")
        }
    }
}
