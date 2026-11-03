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

internal fun validateNewDatabase(database: String, collection: String) {
    validateTransferDatabase(database)
    if (collection.isBlank() || collection.any { it == '\u0000' || it == '$' || it.isISOControl() } ||
        collection.startsWith("system.") || collection.startsWith('.') || collection.endsWith('.') ||
        "$database.$collection".toByteArray(Charsets.UTF_8).size > 235)
        throw InputError("Enter a collection name without control characters, dollar signs, leading/trailing dots, or the system. prefix. Keep the full namespace under 236 bytes.")
}

internal suspend fun MongoSession.createDatabase(database: String, collection: String) {
    validateNewDatabase(database, collection)
    val existing = serverExportDatabases(this) + listOf("local")
    if (existing.any { it.equals(database, ignoreCase = true) }) throw InputError("A database with this name already exists. Choose a different name.")
    try {
        client.getDatabase(database).createCollection(collection)
    } catch (e: CancellationException) { throw e }
    catch (e: MongoCommandException) {
        if (e.errorCode == 13) throw InputError("This account cannot create the database or collection. Check its createCollection permissions.")
        throw InputError("Could not create database: ${connectionError(e)}")
    }
}

internal data class CreateCollectionTarget(val session: MongoSession, val database: String)

internal suspend fun MongoSession.createCollection(database: String, collection: String) {
    validateNewDatabase(database, collection)
    if (collection in collections(database)) throw InputError("A collection or view with this name already exists.")
    try { client.getDatabase(database).createCollection(collection) }
    catch (e: CancellationException) { throw e }
    catch (e: MongoCommandException) {
        if (e.errorCode == 13) throw InputError("This account cannot create collections in this database. Check its createCollection permissions.")
        if (e.errorCode == 48) throw InputError("A collection or view with this name already exists.")
        throw InputError("Could not create collection: ${connectionError(e)}")
    }
}

@Composable
internal fun CreateDatabaseDialog(session: MongoSession, created: (String, String) -> Unit, dismiss: () -> Unit, existingDatabase: String? = null) {
    val creatingCollection = existingDatabase != null
    val title = if (creatingCollection) "Create collection" else "Create database"
    var database by remember { mutableStateOf(existingDatabase.orEmpty()) }
    var collection by remember { mutableStateOf("documents") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = !busy)) {
        Column(Modifier.width(580.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label(title, size = 23, bold = true)
            Spacer(Modifier.height(10.dp)); Label("Connection: ${session.config.name}", size = 13)
            Spacer(Modifier.height(18.dp)); Field("DATABASE NAME", database, enabled = !busy && !creatingCollection) { database = it; message = "" }
            Spacer(Modifier.height(14.dp)); Field(if (creatingCollection) "COLLECTION NAME" else "INITIAL COLLECTION", collection, enabled = !busy) { collection = it; message = "" }
            Spacer(Modifier.height(14.dp)); Label(if (creatingCollection) "Creates an empty collection in the selected database without inserting documents." else "MongoDB creates a database when it contains a collection. This creates an empty collection without inserting documents.", AppColors.muted, 12)
            if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Label(message, AppColors.danger, 12) }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("Cancel", !busy) { dismiss() }
                Spacer(Modifier.weight(1f))
                ToolButton(if (busy) "Creating…" else title, !busy && database.isNotBlank() && collection.isNotBlank(), primary = true) {
                    val db = database.trim(); val name = collection.trim()
                    try { validateNewDatabase(db, name) }
                    catch (e: InputError) { message = e.message.orEmpty(); return@ToolButton }
                    busy = true; message = ""
                    scope.launch {
                        try {
                            if (creatingCollection) session.createCollection(db, name) else session.createDatabase(db, name)
                            created(db, name)
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = if (e is InputError) e.message.orEmpty() else "$title failed: ${connectionError(e)}" }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}
