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

internal data class DocumentCollectionTarget(val session: MongoSession, val database: String, val collection: String)

@Composable
internal fun NewDocumentDialog(target: DocumentCollectionTarget, inserted: (BsonDocument) -> Unit, dismiss: () -> Unit) {
    var draft by remember { mutableStateOf("{}") }
    var reviewed by remember { mutableStateOf<BsonDocument?>(null) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Enter one document in Extended JSON. Leave _id out to generate an ObjectId.") }
    val scope = rememberCoroutineScope()
    fun close() { if (!saving) { if (draft != "{}") discard = true else dismiss() } }
    DialogWindow(onCloseRequest = { close() }, title = "MongoDesk · New document", state = rememberDialogState(width = 850.dp, height = 740.dp)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(24.dp)) {
            Label(if (reviewed == null) "New document" else "Review new document", size = 23, bold = true)
            Spacer(Modifier.height(8.dp)); Label("${target.session.config.name} / ${target.database}.${target.collection}", Color(0xFF8392A5))
            Spacer(Modifier.height(16.dp))
            val document = reviewed
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (document == null) Editor(draft, { draft = it; discard = false }, Modifier.fillMaxWidth().heightIn(min = 430.dp), enabled = !saving)
                else Column(Modifier.fillMaxWidth().background(Color.White).padding(14.dp)) { Label(document.pretty(), size = 13) }
            }
            Spacer(Modifier.height(14.dp)); Label(if (saving) "Inserting document…" else message, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(12.dp))
            if (discard) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Discard this draft?")
                Action("Keep editing") { discard = false }
                Action("Discard", danger = true) { dismiss() }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Cancel", enabled = !saving) { close() }
                if (document == null) Action("Review document", primary = true, enabled = !saving) {
                    try { reviewed = parseNewDocument(draft); message = "Review the document and _id, then confirm insertion into the collection above." }
                    catch (e: InputError) { message = e.message.orEmpty() }
                } else {
                    Action("Back to editing", enabled = !saving) { reviewed = null }
                    Action("Confirm insert", primary = true, enabled = !saving) {
                        saving = true
                        scope.launch {
                            try { inserted(target.session.insertDocument(target.database, target.collection, document)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = connectionError(e) }
                            finally { saving = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeleteDocumentDialog(target: DocumentEditTarget, deleted: () -> Unit, dismiss: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("This permanently deletes this one document. The operation cannot be undone in MongoDesk.") }
    val scope = rememberCoroutineScope()
    DialogWindow(onCloseRequest = { if (!saving) dismiss() }, title = "MongoDesk · Delete document", state = rememberDialogState(width = 780.dp, height = 660.dp)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(24.dp)) {
            Label("Delete document?", size = 23, bold = true)
            Spacer(Modifier.height(8.dp)); Label("${target.session.config.name} / ${target.database}.${target.collection}", Color(0xFF8392A5))
            Spacer(Modifier.height(8.dp)); Label("_id: ${target.original["_id"]?.tableValue()}", bold = true)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).fillMaxWidth().background(Color.White).padding(14.dp).verticalScroll(rememberScrollState())) { Label(target.original.pretty(), size = 12) }
            Spacer(Modifier.height(14.dp)); Label(if (saving) "Deleting document…" else message, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("Cancel", enabled = !saving) { dismiss() }
                Action("Delete permanently", primary = true, enabled = !saving, danger = true) {
                    saving = true
                    scope.launch {
                        try { target.session.deleteDocument(target.database, target.collection, target.original); deleted() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = connectionError(e) }
                        finally { saving = false }
                    }
                }
            }
        }
    }
}
