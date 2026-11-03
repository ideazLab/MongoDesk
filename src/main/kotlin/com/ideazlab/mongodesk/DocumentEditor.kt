package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.*
import org.bson.BsonDocument

internal data class DocumentEditTarget(val session: MongoSession, val database: String, val collection: String, val original: BsonDocument)

@Composable
internal fun DocumentEditorDialog(target: DocumentEditTarget, saved: () -> Unit, dismiss: () -> Unit) {
    val originalJson = remember { target.original.pretty() }
    var draft by remember { mutableStateOf(originalJson) }
    var reviewed by remember { mutableStateOf<BsonDocument?>(null) }
    var saving by remember { mutableStateOf(false) }
    var discardRequested by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Edit the complete document in Extended JSON. Keep _id unchanged.") }
    val scope = rememberCoroutineScope()
    fun requestClose() {
        if (!saving) {
            if (draft != originalJson) discardRequested = true else dismiss()
        }
    }
    DialogWindow(onCloseRequest = { requestClose() }, title = "MongoDesk · Edit document",
        state = rememberDialogState(width = 1080.dp, height = 780.dp)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(24.dp)) {
            Label(if (reviewed == null) "Edit document" else "Review document update", size = 23, bold = true)
            Spacer(Modifier.height(8.dp))
            Label("${target.session.config.name} / ${target.database}.${target.collection}", Color(0xFF8392A5))
            Spacer(Modifier.height(6.dp))
            Label("_id: ${target.original["_id"]?.tableValue()}", Color(0xFF8392A5), 11)
            Spacer(Modifier.height(16.dp))
            val replacement = reviewed
            if (replacement == null) {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Editor(draft, { draft = it; discardRequested = false }, Modifier.fillMaxWidth().heightIn(min = 450.dp), enabled = !saving)
                }
            } else {
                val changes = documentChanges(target.original, replacement)
                Label("${changes.size} changed top-level fields · This replaces the whole document", bold = true)
                Spacer(Modifier.height(8.dp))
                Label(changes.take(8).joinToString(" · ") { "${it.kind}: ${it.field}" } + if (changes.size > 8) " · …" else "", Color(0xFF8392A5), 12)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Label("BEFORE", Color(0xFF8392A5), 11, true); Spacer(Modifier.height(8.dp))
                        Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp).verticalScroll(rememberScrollState())) { Label(originalJson, size = 12) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Label("AFTER", Color(0xFF8392A5), 11, true); Spacer(Modifier.height(8.dp))
                        Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp).verticalScroll(rememberScrollState())) { Label(replacement.pretty(), size = 12) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Label(if (saving) "Saving to MongoDB…" else message, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(12.dp))
            if (discardRequested) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("Discard your unsaved edits?")
                    Action("Keep editing") { discardRequested = false }
                    Action("Discard", danger = true) { dismiss() }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("Cancel", enabled = !saving) { requestClose() }
                if (replacement == null) Action("Review changes", true, !saving && draft != originalJson) {
                    try {
                        val parsed = parseEditedDocument(target.original, draft)
                        if (documentChanges(target.original, parsed).isEmpty()) message = "No document values have changed."
                        else { reviewed = parsed; message = "Review additions, changes and removed fields, then confirm the update." }
                    } catch (e: InputError) { message = e.message.orEmpty() }
                } else {
                    Action("Back to editing", enabled = !saving) { reviewed = null }
                    Action("Confirm update", primary = true, enabled = !saving) {
                        saving = true
                        scope.launch {
                            try {
                                target.session.replaceDocument(target.database, target.collection, target.original, replacement)
                                saved()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = connectionError(e) }
                            finally { saving = false }
                        }
                    }
                }
            }
        }
    }
}
