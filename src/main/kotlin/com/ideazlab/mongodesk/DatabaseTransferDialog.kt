package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.*
import org.bson.Document
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

internal data class DatabaseTransferRequest(val importing: Boolean, val connectionId: String?, val database: String = "", val context: DatabaseTransferContext? = null, val source: DatabaseExportSource = DatabaseExportSource.DATABASE, val serverScope: Boolean = false)

@Composable
internal fun TransferChoice(value: String, options: List<Pair<String, String>>, enabled: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton((options.firstOrNull { it.first == value }?.second ?: "Choose…") + " ▾", enabled) { expanded = !expanded }
        if (expanded) Popup(onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.widthIn(min = 240.dp, max = 500.dp).heightIn(max = 280.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) -> Label(label, if (id == value) WorkspaceBlue else AppColors.text, 12,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = false; choose(id) }.padding(12.dp)) }
            }
        }
    }
}

private fun chooseDatabaseTransferFile(importing: Boolean, suggested: String): Path? {
    val picker = FileDialog(null as Frame?, if (importing) "Import MongoDB database archive" else "Export database", if (importing) FileDialog.LOAD else FileDialog.SAVE)
    if (!importing) picker.file = suggested
    return try { picker.isVisible = true; picker.file?.let { Path.of(picker.directory, it) } } finally { picker.dispose() }
}
private fun chooseDatabaseExportFolder(database: String): Path? {
    val picker = JFileChooser().apply { dialogTitle = "Choose parent folder for database export"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    if (picker.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return picker.selectedFile.toPath().resolve("$database-export-$stamp-${java.util.UUID.randomUUID().toString().take(8)}")
}
private fun chooseToolsDirectory(): String? {
    val picker = JFileChooser().apply { dialogTitle = "MongoDB Database Tools bin folder"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile.absolutePath else null
}
private val toolPreferences: Path get() = Path.of(System.getProperty("user.home"), ".mongodesk", "database-tools.json")
internal fun preferredTools() = runCatching { Document.parse(Files.readString(toolPreferences)).getString("directory") }.getOrNull() ?: DatabaseTools.findDirectory()

@Composable
internal fun DatabaseTransferDialog(request: DatabaseTransferRequest, connections: List<OpenConnection>,
    changed: (String, String, Boolean) -> Unit, dismiss: () -> Unit) {
    val serverScope = request.serverScope
    val importing = request.importing
    val collectionOnly = request.source != DatabaseExportSource.DATABASE
    var step by remember { mutableStateOf(0) }
    var connectionId by remember { mutableStateOf(request.connectionId ?: connections.firstOrNull()?.session?.config?.id.orEmpty()) }
    val open = connections.firstOrNull { it.session.config.id == connectionId }
    var database by remember { mutableStateOf(request.database.ifBlank { open?.databases?.firstOrNull().orEmpty() }) }
    var collection by remember { mutableStateOf(request.context?.collection.orEmpty()) }
    var collections by remember { mutableStateOf(emptyList<String>()) }
    var source by remember { mutableStateOf(request.source) }
    var exportFormat by remember { mutableStateOf(DatabaseExportFormat.defaultFor(request.source)) }
    var path by remember { mutableStateOf<Path?>(null) }
    var archiveInfo by remember { mutableStateOf<ArchiveInfo?>(null) }
    var sourceDatabase by remember { mutableStateOf("") }
    var sourceCollection by remember { mutableStateOf("") }
    val destinationCollection = request.context?.collection.orEmpty()
    var destinationDatabase by remember { mutableStateOf(request.database) }
    var importMode by remember { mutableStateOf(DatabaseImportMode.NEW) }
    var confirmation by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf(preferredTools()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(emptyList<String>()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val service = remember { DatabaseTransferService() }
    var copyConnectionId by remember { mutableStateOf(connectionId) }
    var copyDatabase by remember { mutableStateOf(database) }
    var copyCollection by remember { mutableStateOf(collection + "_copy") }
    var copyAppend by remember { mutableStateOf(false) }
    val databaseFiles = !importing && source == DatabaseExportSource.DATABASE && exportFormat != DatabaseExportFormat.ARCHIVE
    val copying = !importing && exportFormat == DatabaseExportFormat.COPY
    val copyDestination = connections.firstOrNull { it.session.config.id == copyConnectionId }
    val copyNamespace = "$copyDatabase.$copyCollection"
    val needsTools = importing || exportFormat == DatabaseExportFormat.ARCHIVE
    val matchingContext = request.context?.takeIf { it.session.config.id == connectionId && it.database == database && it.collection == collection }
    val confirmationTarget = if (serverScope) "ALL DATABASES" else destinationDatabase
    var serverDatabases by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(connectionId) {
        if (serverScope && !importing && open != null) {
            try { serverDatabases = serverExportDatabases(open.session) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not list all databases. Check listDatabases permissions." }
        }
    }
    val title = if (serverScope) { if (importing) "Import all databases" else "Export all databases" } else if (importing) { if (collectionOnly) "Import collection" else "Import database" } else "Export"
    LaunchedEffect(connectionId, database) {
        collections = emptyList()
        if (!serverScope && open != null && database.isNotBlank()) {
            try { collections = open.session.collections(database) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not list collections for this database." }
        }
    }
    fun validateOptions() {
        if (open == null) throw InputError("Choose an active connection.")
        if (serverScope) {
            if (importing && archiveInfo == null) throw InputError("Choose a server archive.")
        } else if (importing) {
            validateTransferDatabase(sourceDatabase); validateTransferDatabase(destinationDatabase)
            if (archiveInfo?.collections?.containsKey(sourceDatabase) != true) throw InputError("Choose a source database from the archive.")
            if (collectionOnly && sourceCollection !in archiveInfo?.collections?.get(sourceDatabase).orEmpty()) throw InputError("Choose a source collection from the archive.")
        } else {
            validateTransferDatabase(database)
            if (source != DatabaseExportSource.DATABASE && collection.isBlank()) throw InputError("Choose a collection.")
            if (source == DatabaseExportSource.QUERY && matchingContext?.queryLoaded != true) throw InputError("Run a query or pipeline in this collection tab first.")
            if (source == DatabaseExportSource.SELECTED && matchingContext?.selected == null) throw InputError("Select a document in this collection tab first.")
        }
        if (copying) {
            if (copyDestination == null) throw InputError("Choose an active destination connection.")
            validateTransferDatabase(copyDatabase)
            if (copyCollection.isBlank()) throw InputError("Enter a destination collection name.")
        }
        if (!copying && path == null) throw InputError("Choose ${if (importing) "an archive" else "an export destination"}.")
        if (needsTools) DatabaseTools.executable(tools, importing)
    }
    fun runTransfer() {
        try { validateOptions() } catch (e: InputError) { message = e.message.orEmpty(); return }
        val selectedConnection = open ?: return
        val targetFile = path
        if (!copying && targetFile == null) return
        val context = DatabaseTransferContext(selectedConnection.session, database, collection,
            matchingContext?.appliedFilter ?: "{}", matchingContext?.appliedPipeline, matchingContext?.selected, matchingContext?.queryLoaded ?: false)
        busy = true; message = "Starting…"; log = emptyList()
        job = scope.launch {
            var success = false
            try {
                val update: (String) -> Unit = { line -> log = (log + line).takeLast(100); message = line }
                if (needsTools) withContext(Dispatchers.IO) {
                    Files.createDirectories(toolPreferences.parent)
                    Files.writeString(toolPreferences, Document("directory", tools).toJson())
                }
                if (serverScope) {
                    if (importing) restoreServer(selectedConnection.session, targetFile!!, importMode, tools, update)
                    else exportServer(selectedConnection.session, targetFile!!, exportFormat, tools, update)
                } else if (copying) {
                    val count = copyDocuments(context, source, copyDestination!!.session, copyDatabase, copyCollection, copyAppend, update)
                    message = "Copied $count documents to $copyNamespace."
                } else if (importing) service.restore(context, targetFile!!, sourceDatabase, destinationDatabase, importMode, tools, update,
                    sourceCollection.takeIf { collectionOnly }, destinationCollection.takeIf { collectionOnly })
                else service.export(context, source, targetFile!!, tools, exportFormat, update)
                success = true; done = true; if (!copying) message = if (serverScope && importing) "Server import completed." else if (importing) if (collectionOnly) "Collection import completed." else "Database import completed." else "Export completed: ${targetFile?.fileName}"
            } catch (e: CancellationException) { message = if (copying) "Copy cancelled. Documents already copied remain in the destination." else if (importing) "Import cancelled. Changes already written remain in the destination." else "Export cancelled. The incomplete export was removed." }
            catch (e: Exception) { message = if (e is InputError) e.message.orEmpty() else if (copying) "Copy failed. Some documents may already have been copied. Check destination permissions and duplicate _id values." else "Transfer failed. Check the tools and file permissions." }
            finally {
                busy = false
                if (serverScope && importing) changed(connectionId, "", success)
                else if (copying) changed(copyConnectionId, copyDatabase, success)
                else if (importing) changed(connectionId, destinationDatabase, success)
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(900.dp).height(760.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label(title, size = 24, bold = true); Spacer(Modifier.height(8.dp))
            Label("${step + 1} of 3 · ${listOf(if (importing) "Archive and destination" else "Export source", "Options", "Review and run")[step]}", AppColors.muted, 12)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (step) {
                    0 -> {
                        Label(if (importing) "DESTINATION CONNECTION" else "SOURCE CONNECTION", size = 11, bold = true)
                        Spacer(Modifier.height(8.dp))
                        TransferChoice(connectionId, connections.map { it.session.config.id to it.session.config.name }, !busy && !collectionOnly) { id ->
                            connectionId = id; database = connections.first { it.session.config.id == id }.databases.firstOrNull().orEmpty(); collection = ""; source = DatabaseExportSource.DATABASE; exportFormat = DatabaseExportFormat.ARCHIVE; path = null
                        }
                        Spacer(Modifier.height(18.dp))
                        if (importing) {
                            ToolButton("Choose archive…", !busy) {
                                chooseDatabaseTransferFile(true, "")?.let { selected ->
                                    busy = true
                                    job = scope.launch {
                                        try {
                                            val info = withContext(Dispatchers.IO) { inspectDatabaseArchive(selected) }
                                            path = selected; archiveInfo = info; sourceDatabase = info.collections.keys.first()
                                            sourceCollection = info.collections[sourceDatabase].orEmpty().firstOrNull().orEmpty()
                                            if (!collectionOnly) destinationDatabase = sourceDatabase + "_import"
                                            message = ""
                                        } catch (e: Exception) { message = if (e is InputError) e.message.orEmpty() else "Could not read archive." }
                                        finally { busy = false }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp)); Label(path?.toString() ?: "Choose a mongodump archive (.archive or .archive.gz).", AppColors.muted, 12)
                            if (serverScope) {
                                archiveInfo?.let { info ->
                                    Spacer(Modifier.height(12.dp)); Label("All databases in archive (original names):", size = 13, bold = true)
                                    info.collections.forEach { (db, names) -> Label("$db · ${names.size} collections/views", size = 12) }
                                }
                            } else {
                            archiveInfo?.let { info ->
                                Spacer(Modifier.height(16.dp)); Label("DATABASE IN ARCHIVE", size = 11, bold = true)
                                TransferChoice(sourceDatabase, info.collections.map { it.key to "${it.key} (${it.value.size} collections/views)" }, !busy) { sourceDatabase = it; sourceCollection = info.collections[it].orEmpty().firstOrNull().orEmpty(); if (!collectionOnly) destinationDatabase = it + "_import" }
                                if (collectionOnly) {
                                    Spacer(Modifier.height(12.dp)); Label("COLLECTION IN ARCHIVE", size = 11, bold = true)
                                    TransferChoice(sourceCollection, info.collections[sourceDatabase].orEmpty().map { it to it }, !busy) { sourceCollection = it }
                                }
                            }
                            Spacer(Modifier.height(16.dp)); Field("DESTINATION DATABASE", destinationDatabase, enabled = !busy && !collectionOnly) { destinationDatabase = it; confirmation = "" }
                            if (collectionOnly) { Spacer(Modifier.height(12.dp)); Label("Destination collection: $destinationCollection", size = 13) }
                            }
                        } else if (serverScope) {
                            Label("ALL DATABASES", size = 11, bold = true)
                            serverDatabases.forEach { Label(it, size = 13) }
                            Spacer(Modifier.height(12.dp)); Label("Exports all authorized databases. MongoDB's local replication database is excluded. Archives may include users and roles; imports retain original database names. Pause writes for a consistent export.", AppColors.muted, 12)
                        } else {
                            Label("DATABASE", size = 11, bold = true); Spacer(Modifier.height(8.dp))
                            TransferChoice(database, open?.databases.orEmpty().map { it to it }, !busy && !collectionOnly) { database = it; collection = ""; source = DatabaseExportSource.DATABASE; exportFormat = DatabaseExportFormat.ARCHIVE; path = null }
                            Spacer(Modifier.height(12.dp)); Label("COLLECTION (for collection or document exports)", size = 11, bold = true)
                            Spacer(Modifier.height(8.dp))
                            TransferChoice(collection, collections.map { it to it }, !busy && !collectionOnly) { collection = it }
                            Spacer(Modifier.height(16.dp))
                            Column(Modifier.selectableGroup()) {
                                DatabaseExportSource.entries.filter { !collectionOnly || it != DatabaseExportSource.DATABASE }.forEach { option ->
                                    val available = when (option) {
                                        DatabaseExportSource.DATABASE -> true
                                        DatabaseExportSource.COLLECTION -> collection.isNotBlank()
                                        DatabaseExportSource.QUERY -> matchingContext?.queryLoaded == true
                                        DatabaseExportSource.SELECTED -> matchingContext?.selected != null
                                    }
                                    RadioOption(option.label, source == option, !busy && available) { source = option; exportFormat = DatabaseExportFormat.defaultFor(option); path = null }
                                    Label(when (option) {
                                        DatabaseExportSource.DATABASE -> "All collections, documents, indexes, options, and view definitions in this database."
                                        DatabaseExportSource.COLLECTION -> "Export all documents to a file or another collection. MongoDB archives also preserve collection metadata."
                                        DatabaseExportSource.QUERY -> "Re-run the last executed filter or pipeline and export all matching documents, across every page."
                                        DatabaseExportSource.SELECTED -> "Export the currently selected document to a file or another collection."
                                    }, AppColors.muted, 11, modifier = Modifier.padding(start = 30.dp, bottom = 10.dp))
                                }
                            }
                        }
                    }
                    1 -> {
                        if (importing) {
                            Label("IMPORT MODE", size = 11, bold = true)
                            Column(Modifier.selectableGroup()) { DatabaseImportMode.entries.forEach { mode -> RadioOption(if (serverScope && mode == DatabaseImportMode.NEW) "New databases only" else mode.label, importMode == mode, !busy) { importMode = mode; confirmation = "" } } }
                            Spacer(Modifier.height(12.dp)); Label(when (importMode) {
                                DatabaseImportMode.NEW -> if (serverScope) "Every database in the archive must be absent or empty on the destination. All are checked before restore starts." else if (collectionOnly) "The destination collection must not exist. Only the selected archive collection is restored." else "The destination must have no collections. Collections, metadata, indexes, and views will be recreated."
                                DatabaseImportMode.MERGE -> "Documents are inserted into existing collections. Existing documents are not updated; duplicate _id values stop the import."
                                DatabaseImportMode.REPLACE -> if (collectionOnly) "Only the destination collection is dropped and recreated. Other collections are left untouched." else "Collections included in the archive are dropped and recreated. Other destination collections are left untouched."
                            }, AppColors.muted, 12)
                        } else {
                            Label("EXPORT FORMAT", size = 11, bold = true)
                            Column(Modifier.selectableGroup()) {
                                DatabaseExportFormat.available(source).forEach { format ->
                                    RadioOption(format.label, exportFormat == format, !busy) { exportFormat = format; path = null }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            if (copying) {
                                Label("DESTINATION CONNECTION", size = 11, bold = true)
                                TransferChoice(copyConnectionId, connections.map { it.session.config.id to it.session.config.name }, !busy) { copyConnectionId = it; confirmation = "" }
                                Spacer(Modifier.height(12.dp)); Field("DESTINATION DATABASE", copyDatabase, enabled = !busy) { copyDatabase = it; confirmation = "" }
                                Spacer(Modifier.height(12.dp)); Field("DESTINATION COLLECTION", copyCollection, enabled = !busy) { copyCollection = it; confirmation = "" }
                                RadioOption("Create a new collection", !copyAppend, !busy) { copyAppend = false; confirmation = "" }
                                RadioOption("Append to destination (preserve existing documents)", copyAppend, !busy) { copyAppend = true; confirmation = "" }
                            } else {
                            ToolButton(if (databaseFiles) "Choose output folder…" else "Choose destination…", !busy) {
                                val selected = if (databaseFiles) chooseDatabaseExportFolder(if (serverScope) "server" else database)
                                    else chooseDatabaseTransferFile(false, (if (serverScope) "server" else database) + (if (source != DatabaseExportSource.DATABASE && collection.isNotBlank()) ".$collection" else "") + exportFormat.extension)
                                selected?.let { path = it }
                            }
                            Spacer(Modifier.height(10.dp)); Label(path?.toString() ?: "No destination selected.", AppColors.muted, 12)
                            }
                            Spacer(Modifier.height(14.dp)); Label(when (exportFormat) {
                                DatabaseExportFormat.ARCHIVE -> "Restorable with MongoDesk or mongorestore, including indexes and metadata. Pause application writes for a consistent live database export."
                                DatabaseExportFormat.JSON -> "A JSON array using Extended JSON to preserve BSON types. Includes every exported document; indexes and metadata are not included."
                                DatabaseExportFormat.JSON_LINES -> "One Extended JSON document per line. Includes every exported document; indexes and metadata are not included."
                                DatabaseExportFormat.EXCEL -> "An .xlsx workbook with all top-level fields. Cells are stored as text to preserve identifiers and prevent formula execution. Objects and arrays use Extended JSON."
                                DatabaseExportFormat.SQL -> "An SQL script with CREATE TABLE and INSERT statements, ANSI quoted identifiers, and TEXT columns. Objects and arrays use Extended JSON. Missing/null fields become SQL NULL."
                                DatabaseExportFormat.COPY -> "Copies documents and their _id values, without indexes or collection options. The complete source is read before copying starts. Duplicate IDs stop the copy; previous inserts remain."
                                DatabaseExportFormat.CSV -> "Headers include top-level fields from all documents. Missing fields are blank; objects and arrays use Extended JSON text. Values containing commas, quotes, or newlines are quoted."
                            }, AppColors.muted, 12)
                        }
                        if (databaseFiles) {
                            Spacer(Modifier.height(12.dp)); Label((if (serverScope) "Creates one subfolder per database. " else "") + "Creates a new export folder with one file per collection/view, plus manifest.json mapping filenames to collection names. Views export their current documents. Indexes, options, and view definitions require a MongoDB archive. Pause writes if you need a consistent snapshot across collections.", AppColors.muted, 12)
                        }
                        if (needsTools) {
                            Spacer(Modifier.height(22.dp)); Field("MONGODB DATABASE TOOLS · BIN FOLDER", tools, enabled = !busy) { tools = it }
                            ToolButton("Choose tools folder…", !busy) { chooseToolsDirectory()?.let { tools = it } }
                            Spacer(Modifier.height(8.dp)); Label("Uses mongodump/mongorestore 100.3 or later. Connection credentials are passed through an owner-only temporary configuration file.", AppColors.muted, 11)
                        }
                    }
                    2 -> {
                        Label(if (importing) if (serverScope) "Review server import" else "Review database import" else "Review export", size = 19, bold = true)
                        Spacer(Modifier.height(16.dp)); Label("Connection: ${open?.session?.config?.name.orEmpty()}", size = 13)
                        Spacer(Modifier.height(8.dp)); Label(if (serverScope) (if (importing) "All archive databases → this connection · ${importMode.label}" else "All authorized databases (excluding local)") else if (importing) "$sourceDatabase${if (collectionOnly) ".$sourceCollection" else ""} → $destinationDatabase${if (collectionOnly) ".$destinationCollection" else ""} · ${importMode.label}" else "${source.label}: $database${if (source == DatabaseExportSource.DATABASE) "" else ".$collection"}", size = 13)
                        if (serverScope) {
                            Spacer(Modifier.height(8.dp)); Label("Databases: " + (if (importing) archiveInfo?.collections?.keys.orEmpty().toList() else serverDatabases).joinToString(", "), size = 12)
                        }
                        if (databaseFiles) { Spacer(Modifier.height(8.dp)); Label("One file per collection in a new folder; existing exports are preserved.", size = 13) }
                        if (!importing) { Spacer(Modifier.height(8.dp)); Label("Format: ${exportFormat.label}", size = 13) }
                        Spacer(Modifier.height(8.dp)); Label(path?.toString().orEmpty(), AppColors.muted, 12)
                        if (copying) {
                            Spacer(Modifier.height(12.dp)); Label("Destination: ${copyDestination?.session?.config?.name.orEmpty()} → $copyNamespace · ${if (copyAppend) "Append" else "New collection"}", size = 13)
                            if (!done) {
                                Spacer(Modifier.height(12.dp)); Label("Copies are not rolled back if cancelled or interrupted.", AppColors.muted, 12)
                                Field("TYPE $copyNamespace TO CONFIRM", confirmation, enabled = !busy) { confirmation = it }
                            }
                        } else if (importing && !done) {
                            Spacer(Modifier.height(18.dp)); Label((if (serverScope) "Imports every database under its original name, including archived users and roles. " else "") + "Import writes to the destination database. Failed or cancelled imports can leave partial changes; they are not rolled back.", AppColors.muted, 12)
                            Spacer(Modifier.height(12.dp)); Field("TYPE $confirmationTarget TO CONFIRM", confirmation, enabled = !busy) { confirmation = it }
                        } else if (!importing) {
                            Spacer(Modifier.height(16.dp)); Label("Export files contain database data and are not encrypted.", AppColors.muted, 12)
                        }
                        if (log.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Column(Modifier.fillMaxWidth().height(180.dp).background(AppColors.subtle).border(1.dp, WorkspaceLine).padding(10.dp).verticalScroll(rememberScrollState())) {
                                log.takeLast(30).forEach { Label(it, AppColors.secondary, 11) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp)); Label(message.takeLast(1600), if (done) WorkspaceBlue else AppColors.muted, 12)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(if (busy) "Cancel transfer" else if (done) "Close" else "Cancel") { if (busy) job?.cancel() else dismiss() }
                if (step > 0 && !done) ToolButton("Back", !busy) { step--; message = "" }
                Spacer(Modifier.weight(1f))
                if (!done && step < 2) ToolButton("Next", !busy, primary = true) {
                    try {
                        if (step == 0) {
                            if (open == null) throw InputError("Choose an active connection.")
                            if (importing) { if (!serverScope) validateTransferDatabase(destinationDatabase); if (archiveInfo == null) throw InputError("Choose a database archive.") }
                            else if (!serverScope) validateTransferDatabase(database)
                        } else validateOptions()
                        step++; message = ""
                    } catch (e: InputError) { message = e.message.orEmpty() }
                }
                if (!done && step == 2) ToolButton(if (copying) "Copy documents" else if (importing) title else "Export", !busy && (if (copying) confirmation == copyNamespace else !importing || confirmation == confirmationTarget), primary = true, danger = importing && importMode == DatabaseImportMode.REPLACE) { runTransfer() }
            }
        }
    }
}
