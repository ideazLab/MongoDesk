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
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

internal data class BackupManagerRequest(val connectionId: String? = null, val database: String = "", val create: Boolean = false)
private val backupTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private fun backupTime(millis: Long?, zone: String = ZoneId.systemDefault().id) = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)).format(backupTimeFormat) } ?: "—"

@Composable
internal fun BackupManagerDialog(request: BackupManagerRequest, profiles: ConnectionProfiles, connections: List<OpenConnection>, dismiss: () -> Unit) {
    val store = remember { BackupStore() }
    var state by remember { mutableStateOf(BackupState()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<BackupSchedule?>(null) }
    var creating by remember { mutableStateOf(request.create) }
    var removing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var savedProfiles by remember { mutableStateOf(emptyList<SavedConnection>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val snapshot = withContext(Dispatchers.IO) { store.read() to profiles.list() }
                state = snapshot.first; savedProfiles = snapshot.second
                if (selected == null) selected = state.schedules.firstOrNull()?.id
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is InputError) e.message.orEmpty() else "Could not read backup schedules." }
            delay(2000)
        }
    }
    fun action(success: () -> Unit = {}, block: () -> Unit) {
        busy = true; error = ""
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; state = withContext(Dispatchers.IO) { store.read() }; success() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is InputError) e.message.orEmpty() else "Could not update backup schedules. Check folder and background-task permissions." }
            finally { busy = false }
        }
    }
    val current = state.schedules.firstOrNull { it.id == selected }

    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(1060.dp).height(790.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label("Database backup schedules", size = 24, bold = true)
            Spacer(Modifier.height(8.dp)); Label("Runs in the background while you are logged in, even when MongoDesk is closed. The computer must be awake and the server reachable.", AppColors.muted, 12)
            Spacer(Modifier.height(8.dp)); Label("BSON archives include collection data, indexes, and metadata. Every archive is kept; there is no automatic deletion. Missed intervals run once when available again. Live writes are not a consistent snapshot.", AppColors.muted, 12)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("+ Schedule", !busy) { creating = true }
                ToolButton("Edit", !busy && current != null) { editor = current }
                ToolButton(if (current?.enabled == false) "Resume" else "Pause", !busy && current != null) {
                    current?.let { schedule -> action { BackgroundBackups.install(); store.update { s -> s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(enabled = !it.enabled, next = it.next ?: backupStart(it).toEpochMilli()) else it }) } } }
                }
                val running = state.runs.any { it.scheduleId == selected && it.status == "Running" }
                ToolButton("Run now", !busy && current != null && !running && current.requested == false) { current?.let { schedule -> action { BackgroundBackups.install(); store.request(schedule.id); BackgroundBackups.wake() } } }
                ToolButton("Remove", !busy && current != null) { removing = current?.id }
                Spacer(Modifier.weight(1f))
                ToolButton("Register background worker", !busy) { action { BackgroundBackups.install(); BackgroundBackups.wake() } }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxWidth().height(205.dp).border(1.dp, WorkspaceLine)) {
                if (state.schedules.isEmpty()) item { Label("No backup schedules. Create one to choose a saved connection, database, folder, and start time.", AppColors.muted, 12, modifier = Modifier.padding(16.dp)) }
                items(state.schedules, key = { it.id }) { schedule ->
                    val connectionName = savedProfiles.firstOrNull { it.id == schedule.connectionId }?.name ?: "Missing saved connection"
                    Column(Modifier.fillMaxWidth().background(if (selected == schedule.id) AppColors.selection else AppColors.surface).clickable { selected = schedule.id }.padding(12.dp)) {
                        Label("${schedule.name} · $connectionName / ${schedule.database}", size = 13, bold = true)
                        Spacer(Modifier.height(4.dp)); Label("${if (schedule.enabled) "Enabled" else "Paused/completed"} · ${if (schedule.interval == BackupInterval.ONCE) "Once" else "Every ${schedule.every} ${schedule.interval.label.lowercase()}"} · Next: ${backupTime(schedule.next, schedule.zone)} ${schedule.zone}${if (schedule.requested) " · Run queued" else ""}", AppColors.secondary, 11)
                    }
                }
            }
            current?.let { schedule ->
                Spacer(Modifier.height(10.dp)); Label("Destination: ${schedule.folder}", size = 12)
            }
            removing?.let { id ->
                Spacer(Modifier.height(10.dp)); Label("Remove this schedule? Existing archives and history are kept. A running backup will finish.", size = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Keep schedule", !busy) { removing = null }
                    ToolButton("Remove schedule", !busy, danger = true) { action { store.delete(id) }; removing = null; selected = null }
                }
            }
            Spacer(Modifier.height(16.dp)); Label("RECENT BACKUPS · latest 200 results retained", size = 11, bold = true)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, WorkspaceLine)) {
                val runs = state.runs.filter { selected == null || it.scheduleId == selected }.asReversed()
                if (runs.isEmpty()) item { Label("No backups recorded yet. Scheduled runs are checked every minute.", AppColors.muted, 12, modifier = Modifier.padding(12.dp)) }
                items(runs, key = { it.id }) { run ->
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Label("${run.status} · ${run.name} / ${run.database} · ${backupTime(run.started)}", if (run.status == "Failed" || run.status == "Interrupted") AppColors.danger else AppColors.text, 12, true)
                        if (run.file.isNotBlank()) Label(run.file, AppColors.secondary, 11)
                        if (run.message.isNotBlank()) Label(run.message, AppColors.muted, 11)
                    }
                }
            }
            Spacer(Modifier.height(10.dp)); Label(if (busy) "Updating…" else error, AppColors.danger, 12)
            Spacer(Modifier.height(12.dp)); Row { Spacer(Modifier.weight(1f)); ToolButton("Close", !busy) { dismiss() } }
        }
    }
    if (creating || editor != null) BackupScheduleEditor(editor, request, savedProfiles, connections, busy, error, save = { schedule ->
        action(success = { creating = false; editor = null; selected = schedule.id }) {
            validateBackupSchedule(schedule)
            if (schedule.connectionId !in profiles.ids()) throw InputError("Choose an existing saved connection.")
            DatabaseTools.executable(schedule.tools, false)
            Files.createDirectories(Path.of(schedule.folder))
            BackgroundBackups.install()
            store.save(schedule)
            BackgroundBackups.wake()
        }
    }, dismiss = { creating = false; editor = null })
}

@Composable
private fun BackupScheduleEditor(initial: BackupSchedule?, request: BackupManagerRequest, profiles: List<SavedConnection>, connections: List<OpenConnection>, busy: Boolean, externalError: String, save: (BackupSchedule) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: request.database.takeIf { it.isNotBlank() }?.let { "$it backup" }.orEmpty()) }
    var connectionId by remember { mutableStateOf(initial?.connectionId ?: request.connectionId ?: profiles.firstOrNull()?.id.orEmpty()) }
    var database by remember { mutableStateOf(initial?.database ?: request.database) }
    var folder by remember { mutableStateOf(initial?.folder ?: Path.of(System.getProperty("user.home"), "MongoDesk Backups").toString()) }
    var start by remember { mutableStateOf(initial?.let { LocalDateTime.parse(it.start).format(backupTimeFormat) } ?: LocalDateTime.now().plusHours(1).format(backupTimeFormat)) }
    var zone by remember { mutableStateOf(initial?.zone ?: ZoneId.systemDefault().id) }
    var interval by remember { mutableStateOf(initial?.interval ?: BackupInterval.DAYS) }
    var every by remember { mutableStateOf(initial?.every?.toString() ?: "1") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var tools by remember { mutableStateOf(initial?.tools ?: preferredTools()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(profiles) { if (connectionId.isBlank()) connectionId = request.connectionId ?: profiles.firstOrNull()?.id.orEmpty() }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = !busy)) {
        Column(Modifier.width(760.dp).height(810.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label(if (initial == null) "Schedule database backup" else "Edit backup schedule", size = 23, bold = true)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Field("SCHEDULE NAME", name) { name = it }
                Spacer(Modifier.height(12.dp)); Label("SAVED CONNECTION", size = 11, bold = true)
                TransferChoice(connectionId, profiles.map { it.id to it.name }) { connectionId = it; database = "" }
                Spacer(Modifier.height(12.dp)); Field("DATABASE", database) { database = it }
                connections.firstOrNull { it.session.config.id == connectionId }?.let { open -> TransferChoice(database, open.databases.map { it to it }) { database = it } }
                Spacer(Modifier.height(12.dp)); Field("BACKUP FOLDER", folder) { folder = it }
                ToolButton("Choose folder…") {
                    val picker = JFileChooser().apply { dialogTitle = "Backup destination folder"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) folder = picker.selectedFile.absolutePath
                }
                Spacer(Modifier.height(12.dp)); Field("START DATE AND TIME · yyyy-MM-dd HH:mm", start) { start = it }
                Spacer(Modifier.height(12.dp)); Field("TIME ZONE", zone) { zone = it }
                Spacer(Modifier.height(12.dp)); Label("REPEAT", size = 11, bold = true)
                TransferChoice(interval.name, BackupInterval.entries.map { it.name to it.label }) { interval = BackupInterval.valueOf(it) }
                if (interval != BackupInterval.ONCE) { Spacer(Modifier.height(8.dp)); Field("EVERY · ${interval.label.uppercase()}", every) { every = it } }
                Spacer(Modifier.height(12.dp)); Field("DATABASE TOOLS BIN FOLDER", tools) { tools = it }
                RadioOption("Enabled", enabled) { enabled = true }; RadioOption("Paused", !enabled) { enabled = false }
                Label("Archives are unencrypted, owner-only files. Saved encrypted connection details are used by the background worker. Past start times trigger one catch-up run. Daily/weekly repeats follow the selected time zone.", AppColors.muted, 12)
            }
            Spacer(Modifier.height(10.dp)); Label(if (busy) "Saving schedule and registering background worker…" else error.ifBlank { externalError }, AppColors.danger, 12)
            Spacer(Modifier.height(12.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("Cancel", !busy) { dismiss() }; Spacer(Modifier.weight(1f))
                ToolButton("Save schedule", !busy, primary = true) {
                    try {
                        val local = try { LocalDateTime.parse(start, backupTimeFormat) } catch (_: Exception) { throw InputError("Use yyyy-MM-dd HH:mm for the start date and time.") }
                        val schedule = BackupSchedule(initial?.id ?: java.util.UUID.randomUUID().toString(), name, connectionId, database, folder, local.toString(), zone, interval,
                            every.toIntOrNull() ?: 0, enabled, tools = tools)
                        validateBackupSchedule(schedule)
                        val unchanged = initial != null && initial.start == schedule.start && initial.zone == schedule.zone && initial.every == schedule.every && initial.interval == schedule.interval
                        save(schedule.copy(next = if (unchanged) initial?.next ?: backupStart(schedule).toEpochMilli() else backupStart(schedule).toEpochMilli()))
                    } catch (e: Exception) { error = if (e is InputError) e.message.orEmpty() else "Check schedule values and folder path." }
                }
            }
        }
    }
}
