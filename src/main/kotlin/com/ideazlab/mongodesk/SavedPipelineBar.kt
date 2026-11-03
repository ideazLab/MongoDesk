package com.ideazlab.mongodesk

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun SavedPipelineBar(
    library: PipelineLibrary, selectedId: String?, name: String, onNameChange: (String) -> Unit,
    stages: List<PipelineStage>, rawJson: String, jsonMode: Boolean, database: String, collection: String,
    enabled: Boolean, onLoad: (SavedPipeline) -> Unit, onNew: () -> Unit,
    onSaved: (SavedPipeline) -> Unit, message: (String) -> Unit
) {
    var pipelines by remember { mutableStateOf(emptyList<SavedPipeline>()) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedId) {
        confirmDelete = false
        try { pipelines = withContext(Dispatchers.IO) { library.list() } }
        catch (e: InputError) { message(e.message.orEmpty()) }
    }
    val selected = pipelines.firstOrNull { it.id == selectedId }
    val dirty = selected != null && (selected.name != name || selected.stages != stages || selected.rawJson != rawJson || selected.jsonMode != jsonMode)
    fun perform(block: suspend () -> Unit) {
        working = true
        scope.launch {
            try { block(); pipelines = withContext(Dispatchers.IO) { library.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(if (e is InputError) e.message.orEmpty() else "Could not update saved pipelines. Check file permissions.") }
            finally { working = false }
        }
    }
    fun save(asNew: Boolean) = perform {
        val draft = SavedPipeline(id = selectedId ?: java.util.UUID.randomUUID().toString(), name = name,
            stages = stages, rawJson = rawJson, jsonMode = jsonMode, database = database, collection = collection)
        val saved = withContext(Dispatchers.IO) { library.save(draft, asNew) }
        onSaved(saved); message("Saved pipeline: ${saved.name}")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(240.dp)) {
                ConnectionDropdown(pipelines.map { SavedConnection(it.id, it.name) }, selectedId, enabled && !working,
                    newLabel = "New pipeline", searchHint = "Search pipelines…", accessibleLabel = "Saved pipelines",
                    onNew = { onNew() }) { item ->
                    perform { onLoad(withContext(Dispatchers.IO) { library.load(item.id) }); message("Loaded pipeline: ${item.name}") }
                }
            }
            Editor(name, onNameChange, Modifier.width(220.dp).height(42.dp), enabled = enabled && !working)
            Action(if (selected != null) "Save changes" else "Save pipeline", enabled = enabled && !working) { save(false) }
            Action("Save as new", enabled = enabled && !working) { save(true) }
            Action("Delete", enabled = enabled && !working && selected != null, danger = true) { confirmDelete = true }
        }
        Spacer(Modifier.height(6.dp))
        Label(if (working) "Updating saved pipelines…" else if (dirty) "Unsaved changes" else if (selected != null && selected.collection.isNotEmpty())
            "Saved for ${selected.database}.${selected.collection} · Runs against the collection selected above" else "Name your pipeline, then save it for another session.", AppColors.muted, 11)
        if (confirmDelete && selected != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Delete saved pipeline ‘${selected.name}’?")
                Action("Cancel", enabled = !working) { confirmDelete = false }
                Action("Delete permanently", primary = true, enabled = !working, danger = true) {
                    perform {
                        withContext(Dispatchers.IO) { library.delete(selected.id) }
                        confirmDelete = false; onNew(); message("Saved pipeline deleted.")
                    }
                }
            }
        }
    }
}
