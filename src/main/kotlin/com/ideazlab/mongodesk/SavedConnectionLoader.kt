package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val credentialReader = Executors.newSingleThreadExecutor { task ->
    Thread(task, "mongodesk-keychain-reader").apply { isDaemon = true }
}

/** Native keychain calls can wait for OS consent and may ignore thread interruption. */
internal suspend fun <T> awaitCredentialRead(timeoutMillis: Long = 30_000, read: () -> T): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val task = credentialReader.submit {
            try {
                val result = read()
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
        continuation.invokeOnCancellation { task.cancel(true) }
    }
}

suspend fun loadSavedProfile(profiles: ConnectionProfiles, id: String): ConnectionConfig {
    val config = profiles.cached(id) ?: try { awaitCredentialRead { profiles.read(id) } }
    catch (e: TimeoutCancellationException) { throw InputError("Reading saved local connection details timed out. Check file access, then retry.") }
    withContext(Dispatchers.IO) { profiles.markUsed(config) }
    return config
}

suspend fun openSavedWorkspace(
    profiles: ConnectionProfiles, id: String, timeoutMillis: Long = 30_000,
    progress: (String) -> Unit = {}
): ConnectedWorkspace {
    progress(if (profiles.cached(id) != null) "Using saved connection from this session…" else "Reading encrypted local connection details…")
    val config = loadSavedProfile(profiles, id)
    return try { withTimeout(timeoutMillis) { MongoSession.openWorkspace(config, progress) } }
    catch (e: TimeoutCancellationException) { throw InputError("Connection setup exceeded 30 seconds. Check the MongoDB/SSH server and network, then retry.") }
}
