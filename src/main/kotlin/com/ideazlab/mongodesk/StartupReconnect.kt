package com.ideazlab.mongodesk

import kotlinx.coroutines.*

/** A slow or unavailable server cannot prevent another saved connection from opening. */
internal suspend fun reconnectSavedConnections(ids: List<String>, connect: suspend (String) -> Unit,
    onFailure: (String, Exception) -> Unit) = supervisorScope {
    ids.distinct().map { id -> launch {
        try { connect(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(id, e) }
    } }.joinAll()
}
