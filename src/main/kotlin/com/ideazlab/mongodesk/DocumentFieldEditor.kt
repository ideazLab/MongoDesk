package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.*
import org.bson.*

internal enum class FieldEditAction(val title: String) { EDIT("Edit field / object"), ADD("Add field"), DELETE("Delete field") }
internal data class DocumentFieldEditTarget(val document: DocumentEditTarget, val path: List<String>? = null, val action: FieldEditAction = FieldEditAction.EDIT)
internal fun fieldPathLabel(path: List<String>) = path.joinToString("") { "[${BsonDocument("v", BsonString(it)).toJson().substringAfter(':').substringBeforeLast('}').trim()}]" }
internal fun fieldValue(document: BsonDocument, path: List<String>): BsonValue {
    var value: BsonValue = document
    path.forEach { key ->
        value = when (val parent = value) {
            is BsonDocument -> parent[key] ?: throw InputError("This field no longer exists.")
            is BsonArray -> parent.values.getOrNull(key.toIntOrNull() ?: -1) ?: throw InputError("This array item no longer exists.")
            else -> throw InputError("This path does not point to a field.")
        }
    }
    return value
}
internal fun editableFieldPaths(document: BsonDocument): List<List<String>> = buildList {
    val pending = java.util.ArrayDeque<Pair<List<String>, BsonValue>>()
    document.entries.toList().asReversed().filter { it.key != "_id" }.forEach { pending.push(listOf(it.key) to it.value) }
    while (pending.isNotEmpty()) {
        val (path, value) = pending.pop(); add(path)
        val children = when (value) {
            is BsonDocument -> value.entries.map { it.key to it.value }
            is BsonArray -> value.values.mapIndexed { i, v -> i.toString() to v }
            else -> emptyList()
        }
        children.asReversed().forEach { (key, v) -> pending.push((path + key) to v) }
    }
}
internal fun fieldValueJson(value: BsonValue): String = BsonDocument("value", value).pretty().let { it.substring(it.indexOf(':') + 1, it.lastIndexOf('}')).trim() }
internal fun replaceEditedField(original: BsonDocument, path: List<String>, json: String): BsonDocument {
    if (path.isEmpty() || path.first() == "_id") throw InputError("The document _id and its children cannot be edited.")
    val wrapper = try { BsonArray.parse("[$json]") } catch (_: Exception) { throw InputError("Enter one valid Extended JSON value, object, or array.") }
    if (wrapper.size != 1) throw InputError("Enter exactly one Extended JSON value.")
    val replacement = BsonDocument.parse(original.pretty())
    fieldValue(replacement, path) // Check existence before changing anything.
    when (val parent = fieldValue(replacement, path.dropLast(1))) {
        is BsonDocument -> parent[path.last()] = wrapper[0]
        is BsonArray -> parent[path.last().toInt()] = wrapper[0]
        else -> throw InputError("This field cannot be edited.")
    }
    return parseEditedDocument(original, replacement.pretty())
}

internal fun addEditedField(original: BsonDocument, parentPath: List<String>, name: String, json: String): BsonDocument {
    if (parentPath.firstOrNull() == "_id" || (parentPath.isEmpty() && name == "_id")) throw InputError("The document _id and its children cannot be changed.")
    if (name.isBlank() || '\u0000' in name || name.startsWith('$')) throw InputError("Enter a field name without null characters or a leading dollar sign.")
    val replacement = BsonDocument.parse(original.pretty())
    val parent = fieldValue(replacement, parentPath) as? BsonDocument ?: throw InputError("Fields can only be added to an object or the document root.")
    if (parent.containsKey(name)) throw InputError("A field with this name already exists in this object. Use Edit field instead.")
    val values = try { BsonArray.parse("[$json]") } catch (_: Exception) { throw InputError("Enter one valid Extended JSON value, object, or array.") }
    if (values.size != 1) throw InputError("Enter exactly one Extended JSON value.")
    parent[name] = values[0]
    return parseEditedDocument(original, replacement.pretty())
}
internal fun deleteEditedField(original: BsonDocument, path: List<String>): BsonDocument {
    if (path.isEmpty() || path.first() == "_id") throw InputError("The document root, _id, and its children cannot be deleted.")
    val replacement = BsonDocument.parse(original.pretty())
    val parent = fieldValue(replacement, path.dropLast(1)) as? BsonDocument ?: throw InputError("Choose an object field, not an array item.")
    if (!parent.containsKey(path.last())) throw InputError("This field no longer exists.")
    parent.remove(path.last())
    return parseEditedDocument(original, replacement.pretty())
}

@Composable
internal fun DocumentFieldEditorDialog(target: DocumentFieldEditTarget, saved: () -> Unit, dismiss: () -> Unit) {
    val document = target.document
    val adding = target.action == FieldEditAction.ADD
    val deleting = target.action == FieldEditAction.DELETE
    var path by remember { mutableStateOf(target.path) }
    var name by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(if (adding) "null" else target.path?.let { fieldValueJson(fieldValue(document.original, it)) }.orEmpty()) }
    var reviewed by remember { mutableStateOf<BsonDocument?>(null) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val paths = remember {
        val all = editableFieldPaths(document.original)
        when (target.action) {
            FieldEditAction.ADD -> listOf(emptyList<String>()) + all.filter { fieldValue(document.original, it).isDocument }
            FieldEditAction.DELETE -> all.filter { fieldValue(document.original, it.dropLast(1)).isDocument }
            FieldEditAction.EDIT -> all
        }
    }
    val scope = rememberCoroutineScope()
    val initial = if (adding) "null" else path?.let { fieldValueJson(fieldValue(document.original, it)) }.orEmpty()
    fun close() { if (!saving) { if (!deleting && (draft != initial || (adding && name.isNotEmpty()))) discard = true else dismiss() } }
    DialogWindow(onCloseRequest = { close() }, title = "MongoDesk · ${target.action.title}", state = rememberDialogState(width = 920.dp, height = 740.dp)) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
            Label(if (reviewed == null) target.action.title else "Review ${target.action.title.lowercase()}", size = 23, bold = true)
            Spacer(Modifier.height(8.dp)); Label("${document.session.config.name} / ${document.database}.${document.collection}", Color(0xFF8392A5), 12)
            Spacer(Modifier.height(8.dp)); Label("_id: ${document.original["_id"]?.tableValue()}", Color(0xFF8392A5), 11)
            Spacer(Modifier.height(14.dp))
            val selectedPath = path
            if (selectedPath == null) {
                Label(when (target.action) { FieldEditAction.ADD -> "Choose the object to add a field to"; FieldEditAction.DELETE -> "Choose the field to delete"; else -> "Choose the field, object, or array item to edit" }, bold = true)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, WorkspaceLine)) {
                    items(paths) { candidate ->
                        Label(if (candidate.isEmpty()) "Document root" else fieldPathLabel(candidate), size = 12, modifier = Modifier.fillMaxWidth().clickable {
                            path = candidate; draft = if (adding) "null" else fieldValueJson(fieldValue(document.original, candidate)); message = ""
                        }.padding(12.dp))
                    }
                    if (paths.isEmpty()) item { Label("No fields available for this action.", modifier = Modifier.padding(12.dp)) }
                }
            } else {
                Label("${if (adding) "Parent object" else "Path"}: ${if (selectedPath.isEmpty()) "Document root" else fieldPathLabel(selectedPath)}", size = 12, bold = true)
                Spacer(Modifier.height(8.dp)); Label("Use Extended JSON: quote strings; use objects/arrays or BSON wrappers for typed values. Unrelated fields stay unchanged. A concurrent change to the document prevents saving.", Color(0xFF8392A5), 12)
                Spacer(Modifier.height(14.dp))
                val replacement = reviewed
                if (replacement == null) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (adding) {
                        Field("NEW FIELD NAME", name, enabled = !saving) { name = it; discard = false; message = "" }
                        Spacer(Modifier.height(12.dp)); Label("VALUE (EXTENDED JSON)", size = 11, bold = true); Spacer(Modifier.height(8.dp))
                    }
                    if (deleting) {
                        Label("This removes the field and its entire value, including any nested fields. Review the removal before confirming.", Color(0xFFDC3545), 12)
                        Spacer(Modifier.height(12.dp)); Label(initial, size = 12)
                    } else Editor(draft, { draft = it; discard = false }, Modifier.fillMaxWidth().heightIn(min = 300.dp), enabled = !saving)
                } else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("BEFORE" to (if (adding) "(field does not exist)" else initial), "AFTER" to (if (deleting) "(field removed)" else fieldValueJson(fieldValue(replacement, if (adding) selectedPath + name else selectedPath)))).forEach { (title, value) ->
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Label(title, size = 11, bold = true); Spacer(Modifier.height(8.dp))
                            Column(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(12.dp).verticalScroll(rememberScrollState())) { Label(value, size = 12) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp)); Label(if (saving) "Saving…" else message, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (discard) {
                    Label("Discard unsaved changes?")
                    Action("Keep editing") { discard = false }
                    Action("Discard", danger = true) { dismiss() }
                } else {
                    Action("Cancel", enabled = !saving) { close() }
                    Spacer(Modifier.weight(1f))
                    if (reviewed == null) Action("Review changes", primary = true, enabled = !saving && path != null && (deleting || (adding && name.isNotBlank()) || (!adding && draft != initial))) {
                        try {
                            val replacement = when (target.action) {
                                FieldEditAction.ADD -> addEditedField(document.original, path!!, name, draft)
                                FieldEditAction.DELETE -> deleteEditedField(document.original, path!!)
                                FieldEditAction.EDIT -> replaceEditedField(document.original, path!!, draft)
                            }
                            if (replacement == document.original) message = "No values have changed." else { reviewed = replacement; message = "${if (adding) "Add field: " + fieldPathLabel(path!! + name) + ". " else ""}Confirm this change to save it to MongoDB." }
                        } catch (e: InputError) { message = e.message.orEmpty() }
                    } else {
                        Action("Back to editing", enabled = !saving) { reviewed = null }
                        Action(if (deleting) "Confirm field deletion" else "Confirm update", primary = true, enabled = !saving, danger = deleting) {
                            saving = true
                            scope.launch {
                                try { document.session.replaceDocument(document.database, document.collection, document.original, reviewed!!); saved() }
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
}
