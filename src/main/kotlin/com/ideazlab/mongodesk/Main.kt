package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.bson.BsonDocument
import java.awt.FileDialog
import java.awt.Frame

private val Blue: Color @Composable get() = AppColors.blue
private val Ink: Color @Composable get() = AppColors.ink
private val Muted: Color @Composable get() = AppColors.muted
private val Line: Color @Composable get() = AppColors.line
private val Pale: Color @Composable get() = AppColors.pale

fun main(args: Array<String>) {
    if (args.contains("--backup-worker")) {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("apple.awt.UIElement", "true")
        val status = try { runBlocking { BackupEngine().tick() }; 0 } catch (_: Exception) { 1 }
        kotlin.system.exitProcess(status)
    }
    if (isMacDesktop) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "MongoDesk")
    }
    val instance = try {
        SingleInstance.acquire {
            java.awt.EventQueue.invokeLater {
                Frame.getFrames().firstOrNull { it.isDisplayable && it.title == "MongoDesk" }?.let { frame ->
                    frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    frame.isVisible = true
                    frame.toFront()
                    frame.requestFocus()
                    frame.ownedWindows.filter { it.isVisible }.lastOrNull()?.let { it.toFront(); it.requestFocus() }
                    runCatching { java.awt.Desktop.getDesktop().requestForeground(true) }
                }
            }
        }
    } catch (e: Exception) {
        javax.swing.JOptionPane.showMessageDialog(null,
            if (e is IllegalStateException) e.message else "MongoDesk could not initialize its application lock. Check access to your .mongodesk folder.",
            "MongoDesk", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        return
    } ?: return
    instance.use {
        application {
            MongoDeskTheme {
            Window(onCloseRequest = ::exitApplication, title = "MongoDesk", icon = mongoDeskIcon(), state = rememberWindowState(width = 1380.dp, height = 900.dp)) { App(exit = ::exitApplication) }
            }
        }
    }
}

@Composable
internal fun Label(text: String, color: Color = Ink, size: Int = 13, bold: Boolean = false, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
@Composable
internal fun Action(text: String, primary: Boolean = false, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val accent = if (danger) AppColors.danger else if (primary) AppColors.action else Blue
    Box(Modifier.alpha(if (enabled) 1f else .4f).background(if (primary) accent else AppColors.surface, RoundedCornerShape(4.dp))
        .border(1.dp, if (primary || danger) accent else Line, RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 9.dp)) { Label(text, if (primary) Color.White else if (danger) accent else Ink) }
}
@Composable
internal fun RadioOption(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.alpha(if (enabled) 1f else .4f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(18.dp).border(1.5.dp, if (selected) Blue else Muted, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(Blue, CircleShape))
        }
        Label(text, if (selected) Blue else Ink)
    }
}

@Composable
internal fun Editor(value: String, change: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false, enabled: Boolean = true) {
    BasicTextField(value, change, modifier.background(AppColors.surface).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(12.dp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink), enabled = enabled, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
}
@Composable
internal fun Field(title: String, value: String, secret: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
    Label(title, Muted, 11, true); Spacer(Modifier.height(6.dp))
    Editor(value, change, Modifier.fillMaxWidth().heightIn(min = 40.dp), secret, enabled)
    Spacer(Modifier.height(12.dp))
}
internal fun chooseFile(): String? {
    val dialog = FileDialog(null as Frame?, "Select file", FileDialog.LOAD)
    return try { dialog.isVisible = true; dialog.file?.let { dialog.directory + it } } finally { dialog.dispose() }
}

@Composable
internal fun ConnectionDropdown(
    connections: List<SavedConnection>, selectedId: String?, enabled: Boolean = true,
    newLabel: String = "New connection", searchHint: String = "Search connections…", accessibleLabel: String = "Saved connections",
    onNew: () -> Unit, onSelect: (SavedConnection) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var highlighted by remember { mutableStateOf(0) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val matches = connections.filter { it.name.contains(search.trim(), ignoreCase = true) }
    fun choose(connection: SavedConnection) { expanded = false; onSelect(connection) }
    fun chooseNew() { expanded = false; onNew() }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    LaunchedEffect(expanded) { if (expanded) focus.requestFocus() }
    LaunchedEffect(search) { highlighted = 0 }
    LaunchedEffect(highlighted, search, expanded) {
        if (expanded) listState.scrollToItem(highlighted.coerceIn(0..matches.size))
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).alpha(if (enabled) 1f else .4f)
            .background(AppColors.surface, RoundedCornerShape(4.dp)).border(1.dp, if (expanded) Blue else Line, RoundedCornerShape(4.dp))
            .semantics { contentDescription = accessibleLabel; stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(enabled = enabled, role = Role.Button) {
                search = ""; highlighted = 0; expanded = !expanded
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(connections.firstOrNull { it.id == selectedId }?.name ?: newLabel,
                Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp)); Label(if (expanded) "▴" else "▾", Muted)
        }
        if (expanded) Popup(
            alignment = Alignment.TopStart, offset = IntOffset(0, with(density) { 46.dp.roundToPx() }),
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)
        ) {
            Column(Modifier.width(with(density) { anchorWidth.toDp() }).shadow(10.dp, RoundedCornerShape(5.dp))
                .background(AppColors.surface, RoundedCornerShape(5.dp)).border(1.dp, Line, RoundedCornerShape(5.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionDown -> { highlighted = (highlighted + 1).coerceAtMost(matches.size); true }
                        Key.DirectionUp -> { highlighted = (highlighted - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { if (highlighted == 0) chooseNew() else matches.getOrNull(highlighted - 1)?.let { choose(it) }; true }
                        Key.Escape -> { expanded = false; true }
                        else -> false
                    }
                }) {
                BasicTextField(search, { search = it; highlighted = 0 },
                    Modifier.fillMaxWidth().padding(10.dp).border(1.dp, Line, RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "Search ${accessibleLabel.lowercase()}" }.focusRequester(focus).padding(10.dp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink), singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 13.sp),
                    decorationBox = { input -> Box { if (search.isEmpty()) Label(searchHint, Muted); input() } })
                LazyColumn(Modifier.heightIn(max = 220.dp), state = listState) {
                    item(key = "new-connection") {
                        Row(Modifier.fillMaxWidth().background(if (highlighted == 0) AppColors.selection else AppColors.surface)
                            .selectable(selected = connections.none { it.id == selectedId }, role = Role.Button, onClick = { chooseNew() })
                            .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("+ $newLabel", Blue, modifier = Modifier.weight(1f))
                        }
                    }
                    itemsIndexed(matches, key = { _, item -> item.id }) { index, item ->
                        Row(Modifier.fillMaxWidth().background(if (highlighted == index + 1) AppColors.selection else AppColors.surface)
                            .selectable(selected = item.id == selectedId, role = Role.Button, onClick = { choose(item) })
                            .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(item.name, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.id == selectedId) Label("✓", Blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDialog(initial: ConnectionConfig, newRequested: () -> Unit, restoreLast: Boolean = true, saved: (() -> Unit)? = null, connected: (ConnectedWorkspace) -> Unit, onDeleted: (String) -> Unit, dismiss: () -> Unit) {
    var config by remember { mutableStateOf(initial) }
    var deleteRequested by remember { mutableStateOf(false) }
    LaunchedEffect(config.id) { deleteRequested = false }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Save or Connect remembers this connection for your next startup. Details are encrypted locally; normal use does not access Keychain.") }
    var reveal by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val profiles = remember { ConnectionProfiles() }
    var savedConnections by remember { mutableStateOf(emptyList<SavedConnection>()) }
    var migrationNeeded by remember { mutableStateOf(false) }
    var confirmMigration by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        busy = true
        try {
            savedConnections = withContext(Dispatchers.IO) { profiles.list() }
            migrationNeeded = withContext(Dispatchers.IO) { profiles.migrationCandidates().isNotEmpty() }
            // Restore the last-used profile on a fresh startup, without connecting to a server.
            if (restoreLast && !migrationNeeded && savedConnections.none { it.id == initial.id }) {
                val last = withContext(Dispatchers.IO) { profiles.lastUsedId() }
                if (last != null) {
                    config = loadSavedProfile(profiles, last)
                    savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                    message = "Restored ${config.name}. Choose Connect when ready."
                }
            }
        } catch (e: Exception) { message = connectionError(e) }
        finally { busy = false }
    }
    fun runOperation(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = connectionError(e) }
            finally { busy = false }
        }
    }
    DialogWindow(onCloseRequest = { if (!busy) dismiss() }, title = "MongoDesk · Connection", state = rememberDialogState(width = 680.dp, height = 830.dp)) {
        Column(Modifier.fillMaxSize().background(Pale).padding(24.dp)) {
            Label("Connect to MongoDB", size = 24, bold = true)
            Spacer(Modifier.height(8.dp)); Label("Direct connection or an SSH tunnel", Muted)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (migrationNeeded) {
                    Label("Existing profiles need a one-time move from Keychain.", Muted, 12)
                    Spacer(Modifier.height(8.dp))
                    if (!confirmMigration) Action("Move existing profiles from Keychain", enabled = !busy) { confirmMigration = true }
                    else Column {
                        Label("This reads each old entry once, verifies its encrypted local copy, then removes the Keychain entry. macOS may ask for permission during this move.", Muted, 12)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("Cancel", enabled = !busy) { confirmMigration = false }
                            Action("Confirm move", primary = true, enabled = !busy) {
                                runOperation {
                                    try {
                                        val count = migrateKeychainProfiles(profiles)
                                        migrationNeeded = withContext(Dispatchers.IO) { profiles.migrationCandidates().isNotEmpty() }
                                        confirmMigration = false
                                        message = "Moved $count connection(s) to local storage."
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) {
                                        migrationNeeded = withContext(Dispatchers.IO) { profiles.migrationCandidates().isNotEmpty() }
                                        message = "Move incomplete. Existing entries or verified local copies were retained; approve Keychain access and retry to finish cleanup."
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Label("SAVED CONNECTIONS", Muted, 10, true)
                Spacer(Modifier.height(8.dp))
                ConnectionDropdown(savedConnections, config.id, enabled = !busy, onNew = {
                    newRequested()
                }) { saved ->
                    runOperation {
                        try {
                            config = loadSavedProfile(profiles, saved.id)
                            savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                            reveal = true
                            message = "Loaded ${config.name}."
                        } catch (_: Exception) { message = "Could not load this connection. Move legacy profiles or check local credential files." }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Field("CONNECTION NAME", config.name, enabled = !busy) { config = config.copy(name = it) }
                if (savedConnections.any { it.id == config.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Save as new connection", enabled = !busy) {
                            runOperation {
                                try {
                                    config = withContext(Dispatchers.IO) { profiles.saveAsNew(config) }
                                    savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                                    message = "Added ${config.name}. The original connection is unchanged."
                                } catch (_: Exception) { message = "Could not save a new connection. Check the fields and local file permissions." }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!deleteRequested) Action("Delete connection", enabled = !busy, danger = true) { deleteRequested = true }
                    else Column(Modifier.fillMaxWidth().border(1.dp, AppColors.danger, RoundedCornerShape(4.dp)).padding(12.dp)) {
                        Label("Delete ${savedConnections.first { it.id == config.id }.name}?", bold = true)
                        Spacer(Modifier.height(6.dp))
                        Label("This removes the saved profile and its encrypted local credentials, and disconnects it if active. Database data and key files are unchanged.", Muted, 12)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("Cancel", enabled = !busy) { deleteRequested = false }
                            Action("Delete permanently", primary = true, enabled = !busy, danger = true) {
                                runOperation {
                                    val deletedId = config.id
                                    try {
                                        withContext(Dispatchers.IO) { profiles.delete(deletedId) }
                                        savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                                        config = ConnectionConfig(); reveal = true; deleteRequested = false
                                        onDeleted(deletedId)
                                        message = "Connection deleted."
                                    } catch (e: Exception) { message = connectionError(e) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Field("MONGODB URI · authentication and TLS options included", config.uri, secret = !reveal, enabled = !busy) { config = config.copy(uri = it) }
                Action(if (reveal) "Hide URI" else "Show URI", enabled = !busy) { reveal = !reveal }
                Spacer(Modifier.height(12.dp))
                Field("DATABASE (optional; restricts explorer to this database)", config.database, enabled = !busy) { config = config.copy(database = it) }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RadioOption("Direct", selected = !config.ssh, enabled = !busy) { config = config.copy(ssh = false) }
                    RadioOption("SSH tunnel", selected = config.ssh, enabled = !busy) { config = config.copy(ssh = true) }
                }
                Spacer(Modifier.height(16.dp))
                if (config.ssh) {
                    Label("Use one mongodb:// target host reachable from the SSH server.", Muted, 11)
                    Label("TLS hostnames must remain valid through the tunnel. SRV and replica-set discovery are not supported yet.", Muted, 11)
                    Spacer(Modifier.height(12.dp))
                    Field("SSH HOST", config.sshHost, enabled = !busy) { config = config.copy(sshHost = it) }
                    Field("SSH PORT", config.sshPort, enabled = !busy) { config = config.copy(sshPort = it) }
                    Field("SSH USERNAME", config.sshUser, enabled = !busy) { config = config.copy(sshUser = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Action("Private key", !config.passwordAuth, !busy) { config = config.copy(passwordAuth = false, sshSecret = "") }
                        Action("Password", config.passwordAuth, !busy) { config = config.copy(passwordAuth = true, sshSecret = "") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!config.passwordAuth) {
                        Field("PRIVATE KEY PATH", config.keyFile, enabled = !busy) { config = config.copy(keyFile = it) }
                        Action("Choose key file", enabled = !busy) { chooseFile()?.let { config = config.copy(keyFile = it) } }
                        Spacer(Modifier.height(12.dp))
                    }
                    Field(if (config.passwordAuth) "SSH PASSWORD" else "KEY PASSPHRASE (optional)", config.sshSecret, true, !busy) { config = config.copy(sshSecret = it) }
                    Field("KNOWN_HOSTS FILE", config.knownHosts, enabled = !busy) { config = config.copy(knownHosts = it) }
                    Action("Choose known_hosts", enabled = !busy) { chooseFile()?.let { config = config.copy(knownHosts = it) } }
                }
            }
            Spacer(Modifier.height(12.dp)); Label(if (busy) "Working…" else message, Muted, 12)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Cancel", enabled = !busy) { dismiss() }
                Action("Test", enabled = !busy) {
                    runOperation {
                        MongoSession.openWorkspace(config).use { workspace ->
                            message = if (workspace.databases.isEmpty()) "Connected, but no accessible databases were returned. Enter a database name if needed."
                                else "Connection and explorer checks successful · ${workspace.databases.size} database(s)."
                        }
                    }
                }
                Action(if (savedConnections.any { it.id == config.id }) "Save changes" else "Save connection", enabled = !busy) {
                    runOperation {
                        try {
                            withContext(Dispatchers.IO) { profiles.save(config) }
                            savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                            message = "Saved ${config.name}. It will be available after restarting MongoDesk."
                            saved?.invoke()
                        } catch (_: Exception) { message = "Save failed. Check the fields and local file permissions." }
                    }
                }
                Action("Connect", true, !busy) {
                    runOperation {
                        try {
                            withContext(Dispatchers.IO) { profiles.save(config) }
                            savedConnections = withContext(Dispatchers.IO) { profiles.list() }
                        } catch (_: Exception) {
                            message = "Could not save this connection. Check the fields and local file permissions, then try again."
                            return@runOperation
                        }
                        val workspace = MongoSession.openWorkspace(config)
                        try { connected(workspace) }
                        catch (e: Exception) { workspace.close(); throw e }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameWindowScope.App(exit: () -> Unit) {
    val profiles = remember { ConnectionProfiles() }
    val pipelineLibrary = remember { PipelineLibrary() }
    val tabStore = remember { CollectionTabStore() }
    val connections = remember { mutableStateMapOf<String, OpenConnection>() }
    val sidebarStates = remember { mutableStateMapOf<String, SidebarExpansion>() }
    val rememberedConnections = remember { mutableStateListOf<String>() }
    val reconnecting = remember { mutableStateMapOf<String, Boolean>() }
    val connectionErrors = remember { mutableStateMapOf<String, String>() }
    val collectionTabs = remember { mutableStateListOf<CollectionTabState>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var explorerConnectionId by remember { mutableStateOf<String?>(null) }
    var menuNamespace by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var tabsReady by remember { mutableStateOf(false) }
    val scratch = remember { CollectionTabState(CollectionTabRef(connectionId = "", database = "", collection = "")) }
    val currentTab = collectionTabs.firstOrNull { it.ref.id == activeTabId } ?: scratch
    val dashboard = activeTabId == null
    val activeConnection = connections[if (dashboard) explorerConnectionId else currentTab.ref.connectionId]
    val session = activeConnection?.session
    val database = currentTab.ref.database
    val collection = currentTab.ref.collection
    var tab by currentTab.mode
    var documents by currentTab.documents
    var selected by currentTab.selected
    var filter by currentTab.filter
    var appliedFilter by currentTab.appliedFilter
    var page by currentTab.page
    var hasNext by currentTab.hasNext
    var pipeline by currentTab.pipeline
    var pipelineStages by currentTab.stages
    var pipelineJsonMode by currentTab.jsonMode
    var savedPipelineId by currentTab.savedPipelineId
    var pipelineName by currentTab.pipelineName
    var appliedPipeline by currentTab.appliedPipeline
    var resultMode by currentTab.resultMode
    var resultsLoaded by currentTab.loaded
    var documentView by currentTab.documentView
    var savedConnections by remember { mutableStateOf(emptyList<SavedConnection>()) }
    var dashboardPipelines by remember { mutableStateOf(emptyList<SavedPipeline>()) }
    var showWizard by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var editingFromManager by remember { mutableStateOf(false) }
    var lastConfig by remember { mutableStateOf(ConnectionConfig()) }
    var restoreLastConnection by remember { mutableStateOf(false) }
    var serverInfoTarget by remember { mutableStateOf<ServerInfoTarget?>(null) }
    var backupManager by remember { mutableStateOf<BackupManagerRequest?>(null) }
    var dropTarget by remember { mutableStateOf<DropNamespaceTarget?>(null) }
    var createCollectionTarget by remember { mutableStateOf<CreateCollectionTarget?>(null) }
    var createDatabaseTarget by remember { mutableStateOf<MongoSession?>(null) }
    var databaseTransfer by remember { mutableStateOf<DatabaseTransferRequest?>(null) }
    var inspection by remember { mutableStateOf<DocumentInspection?>(null) }
    var fieldEditTarget by remember { mutableStateOf<DocumentFieldEditTarget?>(null) }
    var editTarget by remember { mutableStateOf<DocumentEditTarget?>(null) }
    var newDocumentTarget by remember { mutableStateOf<DocumentCollectionTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<DocumentEditTarget?>(null) }
    var sidebarWidth by remember { mutableStateOf(255f) }
    var editorHeight by remember { mutableStateOf(130f) }
    var aggregationHeight by remember { mutableStateOf(360f) }
    var busy by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Loading…") }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var notice by remember { mutableStateOf("Open a connection to browse your data.") }
    val navigationEnabled = !busy && !showAbout && !showManager && !showWizard && !showConnection && backupManager == null && fieldEditTarget == null && dropTarget == null && createCollectionTarget == null && createDatabaseTarget == null && serverInfoTarget == null && databaseTransfer == null && inspection == null && editTarget == null && newDocumentTarget == null && deleteTarget == null
    val scope = rememberCoroutineScope()
    LaunchedEffect(showConnection, showManager, showWizard, dashboard) {
        try {
            savedConnections = withContext(Dispatchers.IO) { profiles.list() }
            dashboardPipelines = withContext(Dispatchers.IO) { pipelineLibrary.list() }
        } catch (e: Exception) { notice = if (e is InputError) e.message.orEmpty() else "Could not refresh saved connections or pipelines." }
    }
    DisposableEffect(Unit) { onDispose { connections.values.forEach { it.workspace.close() } } }
    fun persistTabs() {
        if (!tabsReady) return
        try {
            connections.forEach { (id, open) -> sidebarStates[id] = open.sidebarState() }
            tabStore.save(collectionTabs.map { it.ref.copy(view = it.documentView.value) }, rememberedConnections.toList(), activeTabId, sidebarStates.toMap())
        }
        catch (_: Exception) { notice = "Could not save open tabs. Check .mongodesk/workspace.json permissions." }
    }
    fun operation(action: suspend () -> Unit) {
        if (busy) return
        busy = true; loadingMessage = "Loading…"
        runningJob = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = connectionError(e) }
            finally { busy = false }
        }
    }
    fun adoptWorkspace(workspace: ConnectedWorkspace) {
        val id = workspace.session.config.id
        val previous = connections.put(id, OpenConnection(workspace, sidebarStates[id]))
        previous?.workspace?.close()
        if (previous != null) collectionTabs.filter { it.ref.connectionId == id }.forEach { it.invalidateResults() }
        if (id !in rememberedConnections) rememberedConnections.add(id)
        connectionErrors.remove(id); persistTabs()
        explorerConnectionId = id; lastConfig = workspace.session.config
        showConnection = false; showWizard = false; showManager = false; editingFromManager = false
        notice = "Connected · ${workspace.session.config.name} · ${connections.size} active connection(s)"
    }
    suspend fun ensureConnection(id: String, revealInSidebar: Boolean = true): OpenConnection {
        connections[id]?.let { if (revealInSidebar) { it.expanded = true; persistTabs() }; return it }
        reconnecting[id] = true; connectionErrors.remove(id)
        try {
            val workspace = openSavedWorkspace(profiles, id, progress = { loadingMessage = it })
            try { adoptWorkspace(workspace) } catch (e: Exception) { workspace.close(); throw e }
            val open = connections.getValue(id)
            for (db in open.expandedDatabases.filterValues { it }.keys.toList()) {
                if (!open.collections.containsKey(db)) open.collections[db] = open.session.collections(db)
            }
            if (revealInSidebar) open.expanded = true
            persistTabs()
            return open
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { connectionErrors[id] = connectionError(e); throw e }
        finally { reconnecting.remove(id) }
    }
    suspend fun loadDocuments(target: CollectionTabState, targetPage: Int, targetFilter: String, size: Int = target.pageSize.value) {
        val ref = target.ref
        val active = connections[ref.connectionId] ?: throw InputError("Reconnect this tab's connection first.")
        val rows = active.session.documents(ref.database, ref.collection, targetFilter, targetPage, size)
        target.documents.value = rows.take(size); target.resetTree(); target.loaded.value = true
        target.hasNext.value = rows.size > size; target.page.value = targetPage; target.pageSize.value = size
        target.appliedFilter.value = targetFilter; target.selected.value = null; target.resultMode.value = "Documents"
        target.documentCount.value = null
        target.documentCount.value = try { active.session.documentCount(ref.database, ref.collection, targetFilter) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null } // Counting can require permissions or more time than fetching a page.
        notice = "${target.documents.value.size} documents · ${active.session.config.name} › ${ref.database}.${ref.collection} · Page ${targetPage + 1}"
    }
    suspend fun loadAggregation(target: CollectionTabState, targetPage: Int, stages: List<BsonDocument>, size: Int = target.pageSize.value) {
        val ref = target.ref
        val active = connections[ref.connectionId] ?: throw InputError("Reconnect this tab's connection first.")
        val started = System.nanoTime()
        val rows = active.session.aggregate(ref.database, ref.collection, stages, targetPage, size)
        target.documents.value = rows.take(size); target.resetTree(); target.loaded.value = true
        target.hasNext.value = rows.size > size; target.page.value = targetPage; target.pageSize.value = size
        target.documentCount.value = null
        target.selected.value = null; target.appliedPipeline.value = stages; target.resultMode.value = "Aggregation"
        notice = "Aggregation · ${stages.size} stages · ${target.documents.value.size} results · ${(System.nanoTime() - started) / 1_000_000} ms"
    }
    suspend fun refreshResults(target: CollectionTabState = currentTab, targetPage: Int = target.page.value, size: Int = target.pageSize.value) {
        if (target.resultMode.value == "Aggregation") loadAggregation(target, targetPage, target.appliedPipeline.value, size)
        else loadDocuments(target, targetPage, target.appliedFilter.value, size)
        // A deletion or external change can remove the final page. Return to a valid page.
        if (targetPage > 0 && target.documents.value.isEmpty()) {
            if (target.resultMode.value == "Aggregation") loadAggregation(target, 0, target.appliedPipeline.value, size)
            else loadDocuments(target, 0, target.appliedFilter.value, size)
        }
    }
    suspend fun activate(target: CollectionTabState, revealInSidebar: Boolean = true) {
        activeTabId = target.ref.id; explorerConnectionId = target.ref.connectionId; menuNamespace = target.ref.connectionId to target.ref.database; persistTabs()
        val active = ensureConnection(target.ref.connectionId, revealInSidebar)
        val db = target.ref.database
        if (!active.collections.containsKey(db)) active.collections[db] = active.session.collections(db)
        if (revealInSidebar) active.expandedDatabases[db] = true
        persistTabs()
        if (!target.loaded.value && target.mode.value != "Aggregation") loadDocuments(target, 0, target.filter.value)
    }
    LaunchedEffect(Unit) {
        busy = true; loadingMessage = "Restoring workspace…"
        try {
            val restored = withContext(Dispatchers.IO) { tabStore.readWorkspace() }
            val available = withContext(Dispatchers.IO) { profiles.ids().toSet() }
            sidebarStates.putAll(restored.sidebar)
            collectionTabs.addAll(restored.tabs.map(::CollectionTabState))
            rememberedConnections.addAll(restored.openConnectionIds.filter { it in available }); activeTabId = restored.activeTabId; tabsReady = true
            val ids = rememberedConnections.toList()
            loadingMessage = "Reconnecting ${ids.size} saved connection(s)…"
            reconnectSavedConnections(ids, connect = { ensureConnection(it, revealInSidebar = false) }, onFailure = { id, error -> connectionErrors[id] = connectionError(error) })
            val selectedTab = collectionTabs.firstOrNull { it.ref.id == activeTabId }
            var tabError: String? = null
            if (selectedTab != null && connections.containsKey(selectedTab.ref.connectionId)) {
                try { activate(selectedTab, revealInSidebar = false) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { tabError = connectionError(e) }
            }
            persistTabs()
            notice = if (connectionErrors.isEmpty()) "Restored ${collectionTabs.size} tabs · ${connections.size} connection(s) reconnected."
                else "${connections.size} connection(s) reconnected · ${connectionErrors.size} failed. Use Connections in the app bar to retry."
            tabError?.let { notice += " Active tab: $it" }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = if (e is InputError) e.message.orEmpty() else "Could not restore the workspace." }
        finally { busy = false }
    }
    suspend fun openCollection(id: String, db: String, name: String) {
        val ref = CollectionTabRef(connectionId = id, database = db, collection = name)
        val target = collectionTabs.firstOrNull { it.ref.sameCollection(ref) } ?: CollectionTabState(ref).also { collectionTabs.add(it); persistTabs() }
        activate(target)
    }
    fun closeTab(target: CollectionTabState) {
        val index = collectionTabs.indexOf(target)
        collectionTabs.remove(target)
        if (activeTabId == target.ref.id) activeTabId = collectionTabs.getOrNull((index - 1).coerceAtLeast(0))?.ref?.id
        persistTabs()
    }
    fun disconnect(id: String) {
        rememberedConnections.remove(id); connectionErrors.remove(id)
        connections.remove(id)?.let { sidebarStates[id] = it.sidebarState(); it.workspace.close() }
        val removed = collectionTabs.filter { it.ref.connectionId == id }
        collectionTabs.removeAll(removed.toSet())
        if (removed.any { it.ref.id == activeTabId }) activeTabId = null
        if (explorerConnectionId == id) explorerConnectionId = connections.keys.firstOrNull()
        persistTabs()
        notice = "Connection closed · ${removed.size} collection tab(s) closed."
    }
    fun connectionDeleted(id: String) {
        disconnect(id); sidebarStates.remove(id); persistTabs(); savedConnections = savedConnections.filterNot { it.id == id }
        notice = "Connection deleted. Its collection tabs were closed."
    }
    fun selectedDocumentTarget(): DocumentEditTarget? {
        val active = session ?: return null
        val doc = selected?.let { documents.getOrNull(it) } ?: return null
        return DocumentEditTarget(active, database, collection, BsonDocument.parse(doc.pretty()))
    }
    fun transferContext(): DatabaseTransferContext? {
        val active = session ?: return null
        if (dashboard || collection.isBlank()) return null
        return DatabaseTransferContext(active, database, collection, appliedFilter,
            if (resultMode == "Aggregation") appliedPipeline.map { BsonDocument.parse(it.pretty()) } else null,
            selected?.let { documents.getOrNull(it)?.let { doc -> BsonDocument.parse(doc.pretty()) } }, resultsLoaded)
    }
    fun inspectDocument(index: Int) {
        val doc = documents.getOrNull(index) ?: return
        selected = index
        inspection = DocumentInspection(BsonDocument.parse(doc.pretty()), "${session?.config?.name.orEmpty()} › $database › $collection",
            if (resultMode == "Aggregation") null else selectedDocumentTarget())
    }
    fun resultContextMenu(index: Int, path: List<String>?): List<ContextMenuItem> {
        if (!navigationEnabled) return emptyList()
        val active = session ?: return emptyList()
        val doc = documents.getOrNull(index)
        val editable = resultMode != "Aggregation"
        val target = doc?.let { DocumentEditTarget(active, database, collection, BsonDocument.parse(it.pretty())) }
        fun choose() { if (doc != null) { selected = index; currentTab.selectedTreePath.value = path?.let { TreePath(index, it) } } }
        return buildList {
            if (doc != null) add(ContextMenuItem("Inspect document") { choose(); inspectDocument(index) })
            if (editable) {
                add(ContextMenuItem("Create document…") { newDocumentTarget = DocumentCollectionTarget(active, database, collection) })
                if (target != null && doc?.containsKey("_id") == true) {
                    add(ContextMenuItem("Edit document…") { choose(); editTarget = target })
                    val direct = path?.takeIf { it.isNotEmpty() && it.first() != "_id" && runCatching { fieldValue(target.original, it) }.isSuccess }
                    if (direct != null) {
                        val value = fieldValue(target.original, direct)
                        val label = when { value.isDocument -> "Edit object…"; value.isArray -> "Edit array…"; else -> "Edit field…" }
                        add(ContextMenuItem(label) { choose(); fieldEditTarget = DocumentFieldEditTarget(target, direct) })
                    } else if (path.isNullOrEmpty() || path.firstOrNull() != "_id") {
                        add(ContextMenuItem("Edit field / object…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target) })
                    }
                    if (path == null) {
                        add(ContextMenuItem("Add field…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target, action = FieldEditAction.ADD) })
                        add(ContextMenuItem("Delete field…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target, action = FieldEditAction.DELETE) })
                    } else {
                        var parentPath = direct ?: path
                        while (parentPath.isNotEmpty() && (parentPath.first() == "_id" || runCatching { fieldValue(target.original, parentPath).isDocument }.getOrDefault(false) == false)) parentPath = parentPath.dropLast(1)
                        val addPath = parentPath.toList()
                        add(ContextMenuItem(if (addPath.isEmpty()) "Add field…" else "Add field to object…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target, addPath, FieldEditAction.ADD) })
                        if (direct != null && fieldValue(target.original, direct.dropLast(1)).isDocument) {
                            add(ContextMenuItem("Delete field…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target, direct, FieldEditAction.DELETE) })
                        } else if (path.isEmpty()) {
                            add(ContextMenuItem("Delete field…") { choose(); fieldEditTarget = DocumentFieldEditTarget(target, action = FieldEditAction.DELETE) })
                        }
                    }
                    add(ContextMenuItem("Delete document…") { choose(); deleteTarget = target })
                }
            }
        }
    }
    fun mutationFinished(message: String, connectionId: String, db: String, coll: String) {
        val target = collectionTabs.firstOrNull { it.ref.connectionId == connectionId && it.ref.database == db && it.ref.collection == coll } ?: return
        operation {
            try { refreshResults(target); if (target.documents.value.isEmpty() && target.page.value > 0) refreshResults(target, target.page.value - 1); notice = message }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { target.invalidateResults(); notice = "$message Results could not refresh; run the query again." }
        }
    }
    inspection?.let { current -> DocumentInspectorDialog(current,
        edit = { inspection = null; editTarget = it }, delete = { inspection = null; deleteTarget = it }, dismiss = { inspection = null }) }
    fieldEditTarget?.let { target -> DocumentFieldEditorDialog(target, saved = {
        fieldEditTarget = null
        mutationFinished("${target.action.title} completed · ${target.document.database}.${target.document.collection}", target.document.session.config.id, target.document.database, target.document.collection)
    }, dismiss = { fieldEditTarget = null }) }
    editTarget?.let { target -> DocumentEditorDialog(target, saved = { editTarget = null; mutationFinished("Document updated successfully · ${target.database}.${target.collection}", target.session.config.id, target.database, target.collection) }, dismiss = { editTarget = null }) }
    newDocumentTarget?.let { target -> NewDocumentDialog(target, inserted = { newDocumentTarget = null; mutationFinished("Document inserted · ${target.database}.${target.collection}", target.session.config.id, target.database, target.collection) }, dismiss = { newDocumentTarget = null }) }
    deleteTarget?.let { target -> DeleteDocumentDialog(target, deleted = { deleteTarget = null; mutationFinished("Document deleted · ${target.database}.${target.collection}", target.session.config.id, target.database, target.collection) }, dismiss = { deleteTarget = null }) }
    if (showManager) ConnectionManagerDialog(profiles, session?.config?.id,
        create = { editingFromManager = true; showManager = false; showWizard = true },
        edit = { config -> lastConfig = config; restoreLastConnection = false; editingFromManager = true; showManager = false; showConnection = true },
        connect = { adoptWorkspace(it) }, deleted = { connectionDeleted(it) },
        changed = { scope.launch { savedConnections = withContext(Dispatchers.IO) { profiles.list() } } }, dismiss = { showManager = false }, connectedIds = connections.keys.toSet())
    if (showWizard) NewConnectionWizard(profiles, saved = { showWizard = false; showManager = true; editingFromManager = false },
        connected = { adoptWorkspace(it) }, dismiss = { showWizard = false; if (editingFromManager) { showManager = true; editingFromManager = false } })
    if (showConnection) ConnectionDialog(lastConfig, newRequested = { showConnection = false; showWizard = true }, restoreLast = restoreLastConnection,
        saved = if (editingFromManager) ({ showConnection = false; showManager = true; editingFromManager = false }) else null,
        connected = { adoptWorkspace(it) }, onDeleted = { connectionDeleted(it) }, dismiss = { showConnection = false; if (editingFromManager) { showManager = true; editingFromManager = false } })
    databaseTransfer?.let { request ->
        DatabaseTransferDialog(request, connections.values.toList(), changed = { id, db, success ->
            operation {
                val open = connections[id] ?: return@operation
                collectionTabs.filter { it.ref.connectionId == id && (db.isBlank() || it.ref.database == db) }.forEach { it.invalidateResults() }
                try {
                    open.databases = (open.session.databases() + listOf(db).filter { it.isNotBlank() }).distinct().sorted()
                    if (db.isBlank()) {
                        open.databases = serverExportDatabases(open.session)
                        open.collections.clear()
                        open.expandedDatabases.filterValues { it }.keys.toList().forEach { name ->
                            if (name in open.databases) open.collections[name] = open.session.collections(name)
                        }
                    } else {
                        open.collections[db] = open.session.collections(db)
                        open.expandedDatabases[db] = true
                    }
                    open.expanded = true; persistTabs()
                    collectionTabs.firstOrNull { it.ref.id == activeTabId && it.ref.connectionId == id && (db.isBlank() || it.ref.database == db) }?.let { refreshResults(it, 0) }
                    notice = if (success) "Data transfer completed · $db" else "Data transfer stopped. Refreshed possible partial changes in $db."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "Import ended; the explorer could not refresh. Reconnect to refresh database contents." }
            }
        }, dismiss = { databaseTransfer = null })
    }
    backupManager?.let { request -> BackupManagerDialog(request, profiles, connections.values.toList(), dismiss = { backupManager = null }) }
    dropTarget?.let { target ->
        DropNamespaceDialog(target, dropped = {
            val id = target.session.config.id
            val removed = collectionTabs.filter { it.ref.connectionId == id && it.ref.database == target.database &&
                (target.collection == null || it.ref.collection == target.collection) }
            collectionTabs.removeAll(removed.toSet())
            if (removed.any { it.ref.id == activeTabId }) activeTabId = null
            connections[id]?.let { open ->
                if (target.collection == null) {
                    open.databases = open.databases.filterNot { it == target.database }
                    if (menuNamespace?.first == id && menuNamespace?.second == target.database) menuNamespace = id to ""
                    open.collections.remove(target.database); open.expandedDatabases.remove(target.database)
                } else {
                    open.collections[target.database] = open.collections[target.database].orEmpty().filterNot { it == target.collection }
                }
            }
            persistTabs(); dropTarget = null
            notice = "${target.title} completed · ${target.namespace} · ${removed.size} tab(s) closed"
        }, dismiss = { dropTarget = null })
    }
    createCollectionTarget?.let { target ->
        CreateDatabaseDialog(target.session, existingDatabase = target.database, created = { db, name ->
            createCollectionTarget = null
            operation {
                connections[target.session.config.id]?.let { open ->
                    val names = try { target.session.collections(db) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { (open.collections[db].orEmpty() + name).distinct().sorted() }
                    open.collections[db] = names
                    open.expanded = true; open.expandedDatabases[db] = true
                    explorerConnectionId = target.session.config.id; persistTabs()
                }
                notice = "Collection created · $db.$name"
            }
        }, dismiss = { createCollectionTarget = null })
    }
    createDatabaseTarget?.let { target ->
        CreateDatabaseDialog(target, created = { db, name ->
            createDatabaseTarget = null
            connections[target.config.id]?.let { open ->
                open.databases = (open.databases + db).distinct().sorted()
                open.collections[db] = listOf(name)
                open.expanded = true; open.expandedDatabases[db] = true
                explorerConnectionId = target.config.id; persistTabs()
            }
            notice = "Database created · $db.$name"
        }, dismiss = { createDatabaseTarget = null })
    }
    serverInfoTarget?.let { target -> ServerInfoDialog(target, dismiss = { serverInfoTarget = null }) }
    val menuOpen = menuNamespace?.first?.let { connections[it] } ?: activeConnection
    val menuDatabase = if (menuNamespace?.first == menuOpen?.session?.config?.id) menuNamespace?.second.orEmpty() else database
    val hasCollection = !dashboard && session != null && collection.isNotBlank()
    val hasDocument = hasCollection && selected?.let { documents.getOrNull(it) } != null
    NativeApplicationHandlers(about = { if (navigationEnabled) showAbout = true }, quit = exit)
    if (showAbout) AboutMongoDeskDialog { showAbout = false }
    MongoDeskMenuBar(NativeMenuState(navigationEnabled, menuOpen != null, menuOpen != null && menuDatabase.isNotBlank(),
        hasCollection, hasDocument, resultMode != "Aggregation", resultsLoaded, dashboard, documentView, menuDatabase, menuOpen?.session?.config?.name.orEmpty())) { command ->
        when (command) {
            AppCommand.NEW_CONNECTION -> { editingFromManager = false; showWizard = true }
            AppCommand.CONNECTIONS -> showManager = true
            AppCommand.CLOSE_TAB -> if (!dashboard) closeTab(currentTab)
            AppCommand.EXIT -> exit()
            AppCommand.ABOUT -> showAbout = true
            AppCommand.DISCONNECT -> menuOpen?.session?.config?.id?.let { disconnect(it); menuNamespace = null }
            AppCommand.SERVER_STATUS -> menuOpen?.let { serverInfoTarget = ServerInfoTarget(it.session, ServerInfoKind.STATUS) }
            AppCommand.BUILD_INFO -> menuOpen?.let { serverInfoTarget = ServerInfoTarget(it.session, ServerInfoKind.BUILD) }
            AppCommand.EXPORT_SERVER, AppCommand.IMPORT_SERVER -> menuOpen?.let { databaseTransfer = DatabaseTransferRequest(command == AppCommand.IMPORT_SERVER, it.session.config.id, serverScope = true) }
            AppCommand.CREATE_DATABASE -> createDatabaseTarget = menuOpen?.session
            AppCommand.CREATE_COLLECTION -> menuOpen?.let { createCollectionTarget = CreateCollectionTarget(it.session, menuDatabase) }
            AppCommand.EXPORT_DATABASE, AppCommand.IMPORT_DATABASE -> menuOpen?.let { databaseTransfer = DatabaseTransferRequest(command == AppCommand.IMPORT_DATABASE, it.session.config.id, menuDatabase) }
            AppCommand.DROP_DATABASE -> menuOpen?.let { dropTarget = DropNamespaceTarget(it.session, menuDatabase) }
            AppCommand.EXPORT_COLLECTION, AppCommand.EXPORT_QUERY, AppCommand.IMPORT_COLLECTION -> if (hasCollection) databaseTransfer = DatabaseTransferRequest(command == AppCommand.IMPORT_COLLECTION, session!!.config.id, database,
                transferContext(), if (command == AppCommand.EXPORT_QUERY) DatabaseExportSource.QUERY else DatabaseExportSource.COLLECTION)
            AppCommand.DROP_COLLECTION -> if (hasCollection) dropTarget = DropNamespaceTarget(session!!, database, collection)
            AppCommand.CREATE_DOCUMENT -> if (hasCollection) newDocumentTarget = DocumentCollectionTarget(session!!, database, collection)
            AppCommand.INSPECT_DOCUMENT -> selected?.let { inspectDocument(it) }
            AppCommand.EDIT_DOCUMENT -> editTarget = selectedDocumentTarget()
            AppCommand.EDIT_FIELD, AppCommand.ADD_FIELD, AppCommand.DELETE_FIELD -> selectedDocumentTarget()?.let { target ->
                fieldEditTarget = DocumentFieldEditTarget(target, action = when (command) { AppCommand.ADD_FIELD -> FieldEditAction.ADD; AppCommand.DELETE_FIELD -> FieldEditAction.DELETE; else -> FieldEditAction.EDIT })
            }
            AppCommand.DELETE_DOCUMENT -> deleteTarget = selectedDocumentTarget()
            AppCommand.RUN -> operation {
                if (tab == "Aggregation") loadAggregation(currentTab, 0, if (pipelineJsonMode) parsePipeline(pipeline) else compileStages(pipelineStages))
                else loadDocuments(currentTab, 0, filter)
            }
            AppCommand.REFRESH -> operation { refreshResults() }
            AppCommand.DASHBOARD -> { activeTabId = null; persistTabs() }
            AppCommand.TABLE, AppCommand.TREE, AppCommand.JSON -> {
                documentView = when (command) { AppCommand.TABLE -> DocumentView.TABLE; AppCommand.TREE -> DocumentView.TREE; else -> DocumentView.JSON }; persistTabs()
            }
            AppCommand.BACKUP_MANAGER -> backupManager = BackupManagerRequest(menuOpen?.session?.config?.id, menuDatabase)
            AppCommand.BACKUP_SCHEDULE -> backupManager = BackupManagerRequest(menuOpen?.session?.config?.id, menuDatabase, true)
            AppCommand.UNDO, AppCommand.REDO, AppCommand.CUT, AppCommand.COPY, AppCommand.PASTE, AppCommand.SELECT_ALL -> sendEditorCommand(command)
        }
    }
    Column(Modifier.fillMaxSize().background(AppColors.surface)) {
        Row(Modifier.fillMaxWidth().height(46.dp).border(1.dp, Line).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(mongoDeskIcon(), "MongoDesk Workspace logo", Modifier.size(36.dp))
                Row { Label("Mongo", size = 20, bold = true); Label("Desk", Blue, 20, true) }
            }
            Spacer(Modifier.weight(1f)); Label("${connections.size} active connection(s)", Muted, 11)
            ToolButton("Export", !busy && connections.isNotEmpty()) { databaseTransfer = DatabaseTransferRequest(false, session?.config?.id ?: explorerConnectionId, database, transferContext()) }
            ToolButton("Import", !busy && connections.isNotEmpty()) { databaseTransfer = DatabaseTransferRequest(true, session?.config?.id ?: explorerConnectionId, database) }
            BackupToolbar(!busy) { create -> backupManager = BackupManagerRequest(session?.config?.id, database, create) }
            ThemePicker()
            ToolButton("Connections", !busy) { showManager = true }
        }
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(sidebarWidth.dp).fillMaxHeight().background(AppColors.subtle).padding(12.dp)) {
                Label("CONNECTIONS", Muted, 10, true)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val roots = connections.keys.toList()
                    if (roots.isEmpty()) Label("No active connections. Use Connections in the app bar to open one.", Muted, 12)
                    roots.forEach { id ->
                        val open = connections[id]
                        val label = open?.session?.config?.name ?: savedConnections.firstOrNull { it.id == id }?.name ?: "Saved connection"
                        CompactContextMenuArea(items = {
                            if (!navigationEnabled || open == null) emptyList() else listOf(
                                SubmenuContextItem("Server Info", listOf(
                                    ContextMenuItem("Server Status") { serverInfoTarget = ServerInfoTarget(open.session, ServerInfoKind.STATUS) },
                                    ContextMenuItem("Build Info") { serverInfoTarget = ServerInfoTarget(open.session, ServerInfoKind.BUILD) }
                                )),
                                ContextMenuItem("Create database…") { createDatabaseTarget = open.session },
                                ContextMenuItem("Export all databases…") { databaseTransfer = DatabaseTransferRequest(false, id, serverScope = true) },
                                ContextMenuItem("Import all databases…") { databaseTransfer = DatabaseTransferRequest(true, id, serverScope = true) },
                                ContextMenuItem("Disconnect") { disconnect(id) }
                            )
                        }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("${if (open?.expanded == true) "▾" else "▸"} ${if (open != null) "●" else if (reconnecting[id] == true) "◌" else "○"} $label", if (id == explorerConnectionId) Blue else Ink, 12, true,
                                Modifier.weight(1f).clickable(enabled = !busy) {
                                    menuNamespace = id to ""
                                    if (open != null) { open.expanded = !open.expanded; explorerConnectionId = id; persistTabs() }
                                    else operation { ensureConnection(id); explorerConnectionId = id }
                                }.padding(vertical = 6.dp))
                        }
                        }
                        connectionErrors[id]?.let { error ->
                            Label("Reconnect failed: $error", AppColors.danger, 11, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                            ToolButton("Retry", !busy) { operation { ensureConnection(id) } }
                            ToolButton("Stop reconnecting at startup", !busy) { rememberedConnections.remove(id); connectionErrors.remove(id); persistTabs() }
                        }
                        if (open?.expanded == true) {
                            open.databases.forEach { db ->
                                val expanded = open.expandedDatabases[db] == true
                                CompactContextMenuArea(fontSize = 13, items = {
                                    if (!navigationEnabled) emptyList() else listOf(
                                        ContextMenuItem("Schedule database backup…") { backupManager = BackupManagerRequest(id, db, true) },
                                        ContextMenuItem("Create collection…") { createCollectionTarget = CreateCollectionTarget(open.session, db) },
                                        ContextMenuItem("Export database…") { databaseTransfer = DatabaseTransferRequest(false, id, db) },
                                        ContextMenuItem("Import database…") { databaseTransfer = DatabaseTransferRequest(true, id, db) },
                                        ContextMenuItem("Drop database…") { dropTarget = DropNamespaceTarget(open.session, db) }
                                    )
                                }) {
                                    Label("${if (expanded) "▾" else "▸"}  $db", bold = true, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        menuNamespace = id to db
                                        if (expanded) { open.expandedDatabases[db] = false; persistTabs() }
                                        else operation {
                                            open.collections[db] = open.session.collections(db); open.expandedDatabases[db] = true; explorerConnectionId = id; persistTabs()
                                        }
                                    }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp))
                                }
                                if (expanded) {
                                    val names = open.collections[db].orEmpty()
                                    Label("Collections (${names.size})", Muted, 10, modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp))
                                    names.forEach { name ->
                                        val isSelected = !dashboard && currentTab.ref.connectionId == id && database == db && collection == name
                                        CompactContextMenuArea(items = {
                                            if (!navigationEnabled || !tabsReady) emptyList() else buildList {
                                                add(ContextMenuItem("Open collection") { operation { openCollection(id, db, name) } })
                                                val context = transferContext()?.takeIf { it.session.config.id == id && it.database == db && it.collection == name }
                                                    ?: DatabaseTransferContext(open.session, db, name)
                                                add(ContextMenuItem("Import collection…") { databaseTransfer = DatabaseTransferRequest(true, id, db, context, DatabaseExportSource.COLLECTION) })
                                                add(ContextMenuItem("Export collection…") { databaseTransfer = DatabaseTransferRequest(false, id, db, context, DatabaseExportSource.COLLECTION) })
                                                if (context.queryLoaded) add(ContextMenuItem("Export query result…") { databaseTransfer = DatabaseTransferRequest(false, id, db, context, DatabaseExportSource.QUERY) })
                                                if (context.selected != null) add(ContextMenuItem("Export selected document…") { databaseTransfer = DatabaseTransferRequest(false, id, db, context, DatabaseExportSource.SELECTED) })
                                                collectionTabs.firstOrNull { it.ref.connectionId == id && it.ref.database == db && it.ref.collection == name }?.let { existing ->
                                                    add(ContextMenuItem("Close collection tab") { closeTab(existing) })
                                                }
                                                add(ContextMenuItem("Drop collection…") { dropTarget = DropNamespaceTarget(open.session, db, name) })
                                            }
                                        }) {
                                        Box(Modifier.fillMaxWidth().background(if (isSelected) AppColors.selection else Color.Transparent)
                                            .clickable(enabled = navigationEnabled && tabsReady) { operation { openCollection(id, db, name) } }.padding(start = 30.dp, top = 7.dp, bottom = 7.dp)) {
                                            Label("▤ $name", if (isSelected) Blue else Ink, 12)
                                        }
                                        }
                                    }
                                }
                            }
                        }
                    
                    }
                }
            }
            Splitter(true) { sidebarWidth = (sidebarWidth + it).coerceIn(200f, 440f) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val paneHeight = (if (tab == "Aggregation") aggregationHeight else editorHeight).coerceIn(90f, (maxHeight.value - 260f).coerceAtLeast(90f))
                Column(Modifier.fillMaxSize()) {
                    val tabScroll = rememberScrollState()
                    Row(Modifier.fillMaxWidth().height(38.dp).background(Pale).horizontalScroll(tabScroll)) {
                        Box(Modifier.fillMaxHeight().background(if (dashboard) AppColors.surface else Color.Transparent).border(1.dp, Line)
                            .clickable(enabled = navigationEnabled) { activeTabId = null; persistTabs() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { Label("Dashboard", if (dashboard) Blue else Muted, 12, dashboard) }
                        collectionTabs.forEach { item ->
                            val active = activeTabId == item.ref.id
                            val name = connections[item.ref.connectionId]?.session?.config?.name ?: savedConnections.firstOrNull { it.id == item.ref.connectionId }?.name ?: "Missing connection"
                            Row(Modifier.fillMaxHeight().background(if (active) AppColors.surface else Color.Transparent).border(1.dp, Line), verticalAlignment = Alignment.CenterVertically) {
                                Label("${item.ref.database}.${item.ref.collection} · $name", if (active) Blue else Muted, 12, active,
                                    Modifier.clickable(enabled = navigationEnabled) { operation { activate(item) } }.padding(horizontal = 12.dp, vertical = 10.dp))
                                Box(Modifier.fillMaxHeight().clickable(enabled = navigationEnabled, role = Role.Button) { closeTab(item) }.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) { Label("×", Muted, 16) }
                            }
                        }
                    }
                    HorizontalScrollbar(rememberScrollbarAdapter(tabScroll), Modifier.fillMaxWidth().height(5.dp))
                    if (dashboard) Dashboard(savedConnections, dashboardPipelines, connections.values.takeIf { it.isNotEmpty() }?.joinToString { it.session.config.name }, connections.values.sumOf { it.databases.size }, !busy,
                        manage = { showManager = true }, create = { editingFromManager = false; showWizard = true },
                        connect = { saved -> operation { ensureConnection(saved.id); explorerConnectionId = saved.id } },
                        pipeline = { saved -> operation {
                            var target = collectionTabs.firstOrNull { it.ref.database == saved.database && it.ref.collection == saved.collection }
                            if (target == null && connections.size == 1 && saved.database.isNotBlank() && saved.collection.isNotBlank()) {
                                target = CollectionTabState(CollectionTabRef(connectionId = connections.keys.first(), database = saved.database, collection = saved.collection))
                                collectionTabs.add(target); persistTabs()
                            }
                            if (target == null) notice = "Open a collection tab, then load ${saved.name} from its Aggregation view."
                            else {
                                activate(target)
                                target.savedPipelineId.value = saved.id; target.pipelineName.value = saved.name
                                target.stages.value = saved.stages; target.pipeline.value = saved.rawJson; target.jsonMode.value = saved.jsonMode
                                target.invalidateResults(); target.mode.value = "Aggregation"; target.resultMode.value = "Aggregation"
                                notice = "Loaded ${saved.name}. Choose Run pipeline."
                            }
                        } }, workspace = { collectionTabs.firstOrNull()?.let { operation { activate(it) } } })
                    else key(currentTab.ref.id) {
                        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("${session?.config?.name ?: savedConnections.firstOrNull { it.id == currentTab.ref.connectionId }?.name ?: "Missing connection"} › $database › $collection", Muted, 12)
                            Spacer(Modifier.weight(1f))
                            if (session != null) ToolButton("Disconnect", !busy) { disconnect(currentTab.ref.connectionId) }
                            else ToolButton("Reconnect", !busy) { operation { activate(currentTab) } }
                        }
                        Row(Modifier.fillMaxWidth().background(Pale).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Documents", "Query", "Aggregation").forEach { mode ->
                                ToolButton(mode, !busy, primary = tab == mode) {
                                    if ((mode == "Aggregation") != (resultMode == "Aggregation")) {
                                        currentTab.invalidateResults(); resultMode = if (mode == "Aggregation") "Aggregation" else "Documents"
                                    }
                                    tab = mode
                                }
                            }
                            ToolButton(if (tab == "Aggregation") "▶ Run pipeline" else "▶ Run query", !busy && session != null, primary = true) {
                                operation {
                                    if (tab == "Aggregation") loadAggregation(currentTab, 0, if (pipelineJsonMode) parsePipeline(pipeline) else compileStages(pipelineStages))
                                    else loadDocuments(currentTab, 0, filter)
                                }
                            }
                            if (busy) ToolButton("Cancel") { runningJob?.cancel(); notice = "Operation cancelled." }
                        }
                        Column(Modifier.fillMaxWidth().height(paneHeight.dp).padding(8.dp).verticalScroll(rememberScrollState())) {
                            if (tab == "Aggregation") {
                                SavedPipelineBar(pipelineLibrary, savedPipelineId, pipelineName, { pipelineName = it }, pipelineStages, pipeline, pipelineJsonMode, database, collection, !busy,
                                    onLoad = { saved -> savedPipelineId = saved.id; pipelineName = saved.name; pipelineStages = saved.stages; pipeline = saved.rawJson; pipelineJsonMode = saved.jsonMode; currentTab.invalidateResults() },
                                    onNew = { savedPipelineId = null; pipelineName = "Untitled pipeline"; pipelineStages = listOf(PipelineStage("\$match", "{}")); pipeline = "[]"; pipelineJsonMode = false; currentTab.invalidateResults() },
                                    onSaved = { saved -> savedPipelineId = saved.id; pipelineName = saved.name }, message = { notice = it })
                                Spacer(Modifier.height(8.dp))
                                AggregationBuilder(pipelineStages, { pipelineStages = it }, pipeline, { pipeline = it }, pipelineJsonMode, { pipelineJsonMode = it }, !busy, { notice = it }, bodyHeight = (paneHeight - 140f).coerceAtLeast(130f).dp)
                            } else Row(Modifier.fillMaxWidth().height((paneHeight - 16f).dp)) {
                                Label("1", Muted, 12, modifier = Modifier.width(28.dp).fillMaxHeight().background(Pale).padding(7.dp))
                                Editor(filter, { filter = it }, Modifier.weight(1f).fillMaxHeight(), enabled = !busy)
                            }
                        }
                        Splitter(false) { if (tab == "Aggregation") aggregationHeight = (paneHeight + it).coerceAtLeast(180f) else editorHeight = (paneHeight + it).coerceAtLeast(90f) }
                        Row(Modifier.fillMaxWidth().background(Pale).horizontalScroll(rememberScrollState()).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(if (resultMode == "Aggregation") "PIPELINE RESULTS" else "DOCUMENTS", Muted, 10, true)
                            ToolButton("Refresh", !busy && session != null && resultsLoaded) { operation { refreshResults() } }
                            if (resultMode != "Aggregation") ToolButton("+ Document", !busy && session != null) { newDocumentTarget = DocumentCollectionTarget(session!!, database, collection) }
                            ToolButton("Inspect", !busy && selected != null) { selected?.let { inspectDocument(it) } }
                            if (resultMode != "Aggregation") {
                                ToolButton("Edit", !busy && session != null && selected != null) { editTarget = selectedDocumentTarget() }
                                ToolButton("Delete", !busy && session != null && selected != null, danger = true) { deleteTarget = selectedDocumentTarget() }
                            }
                            ToolButton("Export", !busy && session != null) { databaseTransfer = DatabaseTransferRequest(false, currentTab.ref.connectionId, database, transferContext(), if (resultsLoaded) DatabaseExportSource.QUERY else DatabaseExportSource.COLLECTION) }
                            DocumentViewPicker(documentView, !busy) { documentView = it; persistTabs() }
                        }
                        Row(Modifier.fillMaxWidth().background(Pale).horizontalScroll(rememberScrollState()).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            val canPage = !busy && session != null && resultsLoaded
                            ToolButton("|‹ First", canPage && page > 0) { operation { refreshResults(targetPage = 0) } }
                            ToolButton("‹ Previous", canPage && page > 0) { operation { refreshResults(targetPage = page - 1) } }
                            Label(if (!resultsLoaded) "No results loaded" else if (documents.isEmpty()) "Page ${page + 1} · No documents" else "Page ${page + 1} · Documents ${page.toLong() * currentTab.pageSize.value + 1}–${page.toLong() * currentTab.pageSize.value + documents.size}", Muted, 11)
                            ToolButton("Next ›", canPage && hasNext) { operation { refreshResults(targetPage = page + 1) } }
                            if (resultsLoaded && !hasNext) Label("End of results", Muted, 11)
                            PageSizePicker(currentTab.pageSize.value, !busy && session != null) { size ->
                                if (resultsLoaded) operation { refreshResults(targetPage = 0, size = size) }
                                else { currentTab.pageSize.value = size; page = 0 }
                            }
                        }
                        val emptyResults = if (session == null) "Select Reconnect to open this collection's connection." else if (resultsLoaded) "No matching documents." else "Run ${if (tab == "Aggregation") "a pipeline" else "a query"} to see results."
                        key(page, currentTab.pageSize.value, currentTab.appliedFilter.value, currentTab.appliedPipeline.value) {
                            if (documentView == DocumentView.TREE) DocumentTreeView(documents, selected, !busy, emptyResults,
                                currentTab.expandedTree, currentTab.selectedTreePath.value, { currentTab.selectedTreePath.value = it },
                                select = { selected = it }, inspect = { inspectDocument(it) }, contextMenu = { index, path -> resultContextMenu(index, path) }, modifier = Modifier.weight(1f).fillMaxWidth())
                            else DenseDocumentGrid(documents, selected, documentView == DocumentView.JSON, !busy, emptyResults,
                                select = { selected = it }, inspect = { inspectDocument(it) }, contextMenu = { index, path -> resultContextMenu(index, path) }, modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth().border(1.dp, Line).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Label(if (selected != null) "1 document selected · Double-click to inspect" else "Select a document · Double-click to inspect", Muted, 11)
                            Spacer(Modifier.weight(1f))
                            val count = currentTab.documentCount.value
                            Label(when {
                                resultMode == "Aggregation" -> "Read-only results · ${documents.size} on this page"
                                !resultsLoaded -> "Run a query to count documents"
                                count == null -> "${documents.size} on this page · Total unavailable"
                                documents.isEmpty() -> "$count matching documents"
                                else -> "Documents ${page.toLong() * currentTab.pageSize.value + 1}–${page.toLong() * currentTab.pageSize.value + documents.size} of $count · ${currentTab.pageSize.value} per page"
                            }, Muted, 11)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().background(Pale).padding(horizontal = 14.dp, vertical = 7.dp)) { Label(if (busy) loadingMessage else notice, Muted, 11) }
    }
}
