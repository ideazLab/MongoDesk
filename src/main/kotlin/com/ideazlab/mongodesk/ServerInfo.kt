package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mongodb.ConnectionString
import com.mongodb.MongoCommandException
import kotlinx.coroutines.*
import org.bson.BsonDocument
import org.bson.BsonInt32
import java.awt.datatransfer.StringSelection
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal enum class ServerInfoKind(val label: String, val command: String) {
    STATUS("Server Status", "serverStatus"), BUILD("Build Info", "buildInfo")
}
internal data class ServerInfoTarget(val session: MongoSession, val kind: ServerInfoKind)

internal suspend fun MongoSession.serverInfo(kind: ServerInfoKind): BsonDocument = try {
    withTimeout(20_000) {
        client.getDatabase("admin").runCommand<BsonDocument>(BsonDocument(kind.command, BsonInt32(1)).append("maxTimeMS", BsonInt32(10_000)))
    }
} catch (_: TimeoutCancellationException) { throw InputError("${kind.label} timed out. Check the connection and retry.") }
catch (e: CancellationException) { throw e }
catch (e: MongoCommandException) {
    if (e.errorCode == 13) throw InputError(if (kind == ServerInfoKind.STATUS)
        "This account cannot run serverStatus. Ask your MongoDB administrator for the serverStatus privilege, typically provided by the clusterMonitor role."
        else "This account cannot run buildInfo on the server. Ask your MongoDB administrator to check its permissions.")
    throw InputError("${kind.label}: ${connectionError(e)}")
}

@Composable
internal fun ServerInfoDialog(target: ServerInfoTarget, dismiss: () -> Unit) {
    var result by remember { mutableStateOf<BsonDocument?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var copyMessage by remember { mutableStateOf("") }
    var updated by remember { mutableStateOf("") }
    var json by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var selectedPath by remember { mutableStateOf<TreePath?>(null) }
    val expanded = remember { mutableStateMapOf<TreePath, Boolean>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    fun load() {
        if (busy) return
        busy = true; error = ""; result = null; selected = null; selectedPath = null; expanded.clear()
        job = scope.launch {
            try {
                result = target.session.serverInfo(target.kind)
                expanded[TreePath(0)] = true
                updated = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            } catch (e: CancellationException) { error = "Request cancelled." }
            catch (e: Exception) { error = if (e is InputError) e.message.orEmpty() else connectionError(e) }
            finally { busy = false }
        }
    }
    fun close() { job?.cancel(); dismiss() }
    LaunchedEffect(target) { load() }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(1040.dp).height(760.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(22.dp)) {
            Label(target.kind.label, size = 23, bold = true)
            Spacer(Modifier.height(7.dp)); Label("${target.session.config.name} · ${runCatching { ConnectionString(target.session.config.uri).hosts.joinToString() }.getOrDefault("")}", AppColors.muted, 12)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton("Refresh", !busy) { load() }
                if (busy) ToolButton("Cancel request") { job?.cancel() }
                ToolButton(if (json) "Tree view" else "JSON view", result != null) { json = !json }
                ToolButton("Copy JSON", result != null && !busy) { result?.let { document -> scope.launch {
                    try { clipboard.setClipEntry(ClipEntry(StringSelection(document.pretty()))); copyMessage = "Copied JSON" }
                    catch (_: Exception) { copyMessage = "Could not copy JSON. Try again." }
                } } }
                Spacer(Modifier.weight(1f)); Label(if (busy) "Loading…" else if (updated.isNotEmpty() && error.isEmpty()) "Updated $updated" else "", AppColors.muted, 11)
            }
            Spacer(Modifier.height(14.dp))
            val document = result
            if (document == null) Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, WorkspaceLine).padding(24.dp), contentAlignment = Alignment.Center) {
                Label(if (busy) "Loading ${target.kind.label.lowercase()}…" else error.ifEmpty { "Select Refresh to load server information." }, if (error.isNotEmpty()) AppColors.danger else AppColors.muted, 13)
            } else if (json) {
                val vertical = rememberScrollState(); val horizontal = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.subtle).border(1.dp, WorkspaceLine)) {
                    SelectionContainer(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal).padding(14.dp)) {
                        BasicText(document.pretty(), style = TextStyle(color = AppColors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            } else DocumentTreeView(listOf(document), selected, !busy, "", expanded, selectedPath, { selectedPath = it },
                select = { selected = it }, inspect = {}, modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, WorkspaceLine))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(if (copyMessage.isNotEmpty()) copyMessage else "Read-only · admin.${target.kind.command} · Server selected by this connection", AppColors.muted, 11)
                Spacer(Modifier.weight(1f)); ToolButton("Close") { close() }
            }
        }
    }
}
