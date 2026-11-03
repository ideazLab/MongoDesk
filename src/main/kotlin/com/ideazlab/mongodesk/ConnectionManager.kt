package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private fun transferFile(save: Boolean): Path? {
    val dialog = FileDialog(null as Frame?, if (save) "Export MongoDesk connections" else "Import MongoDesk connections", if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) dialog.file = "connections.mongodesk"
    return try { dialog.isVisible = true; dialog.file?.let { Path.of(dialog.directory, it) } } finally { dialog.dispose() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConnectionManagerDialog(profiles: ConnectionProfiles, activeId: String?,
    create: () -> Unit, edit: (ConnectionConfig) -> Unit, connect: (ConnectedWorkspace) -> Unit,
    deleted: (String) -> Unit, changed: () -> Unit, dismiss: () -> Unit, connectedIds: Set<String> = setOfNotNull(activeId)) {
    var entries by remember { mutableStateOf(emptyList<SavedConnection>()) }
    var details by remember { mutableStateOf(emptyMap<String, ConnectionConfig>()) }
    var selectedId by remember { mutableStateOf(activeId) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Select a connection to edit or connect. Backups are password-encrypted and include credentials.") }
    var confirmDelete by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val selected = entries.firstOrNull { it.id == selectedId }
    val matches = entries.filter { it.name.contains(search.trim(), true) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = if (e is InputError) e.message.orEmpty() else "Operation failed. Check the connection files and try again." }
            finally { busy = false }
        }
    }
    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val saved = profiles.list()
            saved to saved.mapNotNull { entry -> runCatching { entry.id to profiles.read(entry.id) }.getOrNull() }.toMap()
        }
        entries = loaded.first; details = loaded.second; changed()
    }
    LaunchedEffect(Unit) { try { reload() } catch (_: Exception) { message = "Could not read saved connections." } }
    fun open(id: String) = run {
        val workspace = openSavedWorkspace(profiles, id, progress = { message = it })
        try { connect(workspace) } catch (e: Exception) { workspace.close(); throw e }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(1000.dp).height(620.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(20.dp)) {
            Label("Connection Manager", size = 23, bold = true); Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("+ New connection", !busy) { create() }
                ToolButton("Edit", !busy && selected != null) { run { edit(withContext(Dispatchers.IO) { profiles.read(selected!!.id) }) } }
                ToolButton("Duplicate", !busy && selected != null) { run {
                    val added = withContext(Dispatchers.IO) { profiles.saveAsNew(profiles.read(selected!!.id)) }
                    reload(); selectedId = added.id; message = "Duplicated ${added.name}."
                } }
                ToolButton("Delete", !busy && selected != null, danger = true) { confirmDelete = true }
                ToolButton("Import", !busy) { message = ""; transfer = "Import"; password = ""; repeated = "" }
                ToolButton("Export selected", !busy && selected != null) { message = ""; transfer = "Export selected"; password = ""; repeated = "" }
                ToolButton("Export all", !busy && entries.isNotEmpty()) { message = ""; transfer = "Export all"; password = ""; repeated = "" }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Filter connections", AppColors.muted, 12); Spacer(Modifier.width(12.dp))
                Editor(search, { search = it }, Modifier.weight(1f).height(38.dp), enabled = !busy)
                Spacer(Modifier.width(14.dp)); Label("${matches.size} matches", size = 12)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().background(AppColors.pale).padding(12.dp)) {
                Label("NAME", size = 11, bold = true, modifier = Modifier.weight(1f))
                Label("DB SERVER", size = 11, bold = true, modifier = Modifier.width(230.dp))
                Label("SECURITY", size = 11, bold = true, modifier = Modifier.width(200.dp))
                Label("STATUS", size = 11, bold = true, modifier = Modifier.width(150.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, WorkspaceLine)) {
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(matches, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().background(if (selectedId == entry.id) AppColors.selection else AppColors.surface)
                            .combinedClickable(enabled = !busy, onClick = { selectedId = entry.id; confirmDelete = false }, onDoubleClick = { selectedId = entry.id; open(entry.id) }).padding(12.dp)) {
                            Label(entry.name, size = 13, bold = true, modifier = Modifier.weight(1f))
                            val config = details[entry.id]
                            val parsed = config?.let { runCatching { com.mongodb.ConnectionString(it.uri) }.getOrNull() }
                            Label(parsed?.hosts?.joinToString() ?: "Details unavailable", AppColors.muted, 12, modifier = Modifier.width(230.dp))
                            Label(if (config == null) "Unavailable" else listOfNotNull(if (config.ssh) "SSH" else "Direct", if (parsed?.credential != null) "Authenticated" else null, if (parsed?.sslEnabled == true) "TLS" else null).joinToString(" · "), AppColors.muted, 12, modifier = Modifier.width(200.dp))
                            Label(if (entry.id in connectedIds) "● Connected" else "Saved", if (entry.id in connectedIds) WorkspaceBlue else AppColors.muted, 12, modifier = Modifier.width(150.dp))
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                if (matches.isEmpty()) Label(if (entries.isEmpty()) "No saved connections. Create or import one to begin." else "No matching connections.", AppColors.muted, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(12.dp))
            if (confirmDelete && selected != null) {
                Label("Delete “${selected.name}” and its saved credentials? This also disconnects it if active.", size = 12)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Cancel", !busy) { confirmDelete = false }
                    ToolButton("Delete permanently", !busy, danger = true) { run {
                        val id = selected.id
                        withContext(Dispatchers.IO) { profiles.delete(id) }; deleted(id)
                        selectedId = null; confirmDelete = false; reload(); message = "Connection deleted."
                    } }
                }
            }
            Label(if (busy) "Working… $message" else message, AppColors.muted, 12)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("${entries.size} saved connections", AppColors.muted, 12)
                Spacer(Modifier.weight(1f))
                ToolButton("Close", !busy) { dismiss() }
                ToolButton("Connect", !busy && selected != null, primary = true) { selected?.let { open(it.id) } }
            }
        }
    }
    transfer?.let { mode ->
        val importing = mode == "Import"
        Dialog(onDismissRequest = { if (!busy) { transfer = null; password = ""; repeated = "" } }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Column(Modifier.width(560.dp).height(390.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(22.dp)) {
                Label(mode, size = 21, bold = true); Spacer(Modifier.height(12.dp))
                Label(if (importing) "Enter the backup password. Imported profiles are added as new connections; existing profiles are preserved." else "The backup includes MongoDB and SSH credentials. Choose a password to protect it. Private key files are not included.", AppColors.muted, 12)
                Spacer(Modifier.height(14.dp))
                Label("BACKUP PASSWORD", size = 10); Editor(password, { password = it }, Modifier.fillMaxWidth().height(40.dp), secret = true, enabled = !busy)
                if (!importing) { Spacer(Modifier.height(8.dp)); Label("CONFIRM PASSWORD", size = 10); Editor(repeated, { repeated = it }, Modifier.fillMaxWidth().height(40.dp), secret = true, enabled = !busy) }
                Spacer(Modifier.weight(1f))
                Label(message, AppColors.muted, 11)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Cancel", !busy) { transfer = null; password = ""; repeated = "" }
                    ToolButton("Choose file…", !busy && password.isNotEmpty() && (importing || (password.length >= 8 && password == repeated)), primary = true) {
                        val file = transferFile(!importing)
                        if (file != null) run {
                            val secret = password.toCharArray()
                            try {
                                if (importing) {
                                    val configs = withContext(Dispatchers.IO) {
                                        if (Files.size(file) > 8_000_000) throw InputError("Connection backup is too large.")
                                        ConnectionTransfer.decode(Files.readString(file), secret)
                                    }
                                    var count = 0
                                    try {
                                        for (config in configs) {
                                            val added = withContext(Dispatchers.IO) { profiles.saveAsNew(config) }; selectedId = added.id; count++
                                        }
                                        message = "Imported $count connection(s)."
                                    } catch (_: Exception) { message = "Imported $count of ${configs.size} connection(s). Import stopped; check local storage before retrying." }
                                    reload()
                                } else {
                                    val ids = if (mode == "Export all") entries.map { it.id } else listOfNotNull(selected?.id)
                                    withContext(Dispatchers.IO) {
                                        val backup = ConnectionTransfer.encode(ids.map { profiles.read(it) }, secret)
                                        val temp = Files.createTempFile(file.toAbsolutePath().parent, ".mongodesk-export-", ".tmp")
                                        try { Files.writeString(temp, backup); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
                                        finally { Files.deleteIfExists(temp) }
                                    }
                                    message = "Exported ${ids.size} connection(s) to ${file.fileName}."
                                }
                                transfer = null; password = ""; repeated = ""
                            } finally { secret.fill('\u0000') }
                        }
                    }
                }
            }
        }
    }
}
