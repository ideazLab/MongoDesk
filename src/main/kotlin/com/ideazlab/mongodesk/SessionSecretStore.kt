package com.ideazlab.mongodesk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Process-local cache only. Successful writes/deletions update it; failures preserve prior state. */
class SessionSecretStore(private val backing: SecretStore) : SecretStore {
    private val values = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val generation = AtomicLong()
    fun peek(id: String): String? = values[id]
    private fun lock(id: String): Any = locks.computeIfAbsent(id) { Any() }
    override fun get(id: String): String {
        values[id]?.let { return it }
        return synchronized(lock(id)) {
            values[id] ?: run {
                val version = generation.get()
                val secret = backing.get(id)
                synchronized(values) { if (generation.get() == version) values[id] = secret }
                secret
            }
        }
    }
    override fun put(id: String, secret: String) = synchronized(lock(id)) {
        // Connect saves the selected profile; unchanged values must not rewrite local credentials.
        if (values[id] != secret) {
            val version = generation.get()
            backing.put(id, secret)
            synchronized(values) { if (generation.get() == version) values[id] = secret }
        }
    }
    override fun delete(id: String) = synchronized(lock(id)) {
        backing.delete(id)
        values.remove(id)
        Unit
    }
    fun clear() {
        synchronized(values) {
            generation.incrementAndGet()
            values.clear()
        }
    }
}

internal object SessionCredentials {
    val store = SessionSecretStore(LocalSecretStore())
    init {
        Runtime.getRuntime().addShutdownHook(Thread({ store.clear() }, "mongodesk-clear-session-credentials"))
    }
}
