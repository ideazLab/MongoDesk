package com.ideazlab.mongodesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mongodb.MongoCommandException
import kotlinx.coroutines.*

internal data class DropNamespaceTarget(val session: MongoSession, val database: String, val collection: String? = null) {
    val namespace: String get() = collection?.let { "$database.$it" } ?: database
    val title: String get() = if (collection == null) "Drop database" else "Drop collection"
}

internal suspend fun dropNamespace(target: DropNamespaceTarget, confirmation: String) {
    if (target.database.isBlank() || target.database.contains('\u0000') || target.collection?.let { it.isBlank() || it.contains('\u0000') } == true)
        throw InputError("Choose a valid database or collection.")
    if (confirmation != target.namespace) throw InputError("Type ${target.namespace} exactly to confirm.")
    val database = target.session.client.getDatabase(target.database)
    if (!database.writeConcern.isAcknowledged) throw InputError("Drop requires acknowledged writes. Update the connection write concern first.")
    try {
        if (target.collection == null) database.drop()
        else database.getCollection<org.bson.BsonDocument>(target.collection).drop()
    } catch (e: CancellationException) { throw e }
    catch (e: MongoCommandException) {
        if (e.errorCode == 26) return // Already removed by another client.
        if (e.errorCode == 13) throw InputError("This account does not have permission to ${target.title.lowercase()} on this server.")
        throw InputError("${target.title} failed: ${connectionError(e)}")
    }
}

@Composable
internal fun DropNamespaceDialog(target: DropNamespaceTarget, dropped: () -> Unit, dismiss: () -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = !busy)) {
        Column(Modifier.width(620.dp).background(Color.White).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label(target.title, size = 23, bold = true)
            Spacer(Modifier.height(12.dp)); Label("Connection: ${target.session.config.name}", size = 13)
            Spacer(Modifier.height(8.dp)); Label("Target: ${target.namespace}", size = 13, bold = true)
            Spacer(Modifier.height(16.dp)); Label(if (target.collection == null)
                "Permanently deletes this database and all its collections, documents, indexes, and views. MongoDesk cannot undo this operation."
                else "Permanently deletes this collection and its documents and indexes. MongoDesk cannot undo this operation.", Color(0xFFDC3545), 13)
            Spacer(Modifier.height(16.dp)); Label("Related open collection tabs will be closed. Unsaved work in those tabs will be lost.", Color(0xFF8392A5), 12)
            Spacer(Modifier.height(16.dp)); Field("TYPE ${target.namespace} TO CONFIRM", confirmation, enabled = !busy) { confirmation = it; error = "" }
            if (error.isNotBlank()) { Spacer(Modifier.height(12.dp)); Label(error, Color(0xFFDC3545), 12) }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("Cancel", !busy) { dismiss() }
                Spacer(Modifier.weight(1f))
                ToolButton(if (busy) "Dropping…" else target.title, !busy && confirmation == target.namespace, danger = true) {
                    busy = true; error = ""
                    scope.launch {
                        try { dropNamespace(target, confirmation); dropped() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = if (e is InputError) e.message.orEmpty() else "${target.title} failed: ${connectionError(e)}. Refresh the sidebar to check the server state before retrying." }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}
