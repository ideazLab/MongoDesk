package com.ideazlab.mongodesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.event.KeyEvent

internal enum class AppCommand {
    NEW_CONNECTION, CONNECTIONS, CLOSE_TAB, EXIT, ABOUT, SETTINGS, PROJECT_WEBSITE,
    DISCONNECT, SERVER_STATUS, BUILD_INFO, EXPORT_SERVER, IMPORT_SERVER,
    CREATE_DATABASE, EXPORT_DATABASE, IMPORT_DATABASE, DROP_DATABASE, CREATE_COLLECTION,
    EXPORT_COLLECTION, EXPORT_QUERY, IMPORT_COLLECTION, DROP_COLLECTION,
    CREATE_DOCUMENT, INSPECT_DOCUMENT, EDIT_DOCUMENT, EDIT_FIELD, ADD_FIELD, DELETE_FIELD, DELETE_DOCUMENT,
    RUN, REFRESH, DASHBOARD, TABLE, TREE, JSON, BACKUP_SCHEDULE, BACKUP_MANAGER,
    UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL
}
internal data class NativeMenuState(val available: Boolean, val connection: Boolean, val database: Boolean, val collection: Boolean,
    val document: Boolean, val editable: Boolean, val loaded: Boolean, val dashboard: Boolean, val view: DocumentView,
    val databaseName: String = "", val connectionName: String = "")
internal val isMacDesktop get() = System.getProperty("os.name").startsWith("Mac")
private fun shortcut(key: Key, shift: Boolean = false, alt: Boolean = false) = if (isMacDesktop) null else KeyShortcut(key, ctrl = true, shift = shift, alt = alt)

private fun installMacShortcuts(menu: java.awt.Menu) {
    val bindings = mapOf("New connection…" to (KeyEvent.VK_N to false), "Connections…" to (KeyEvent.VK_K to false),
        "Close collection tab" to (KeyEvent.VK_W to false), "Run query / pipeline" to (KeyEvent.VK_ENTER to false),
        "Refresh results" to (KeyEvent.VK_R to false), "Create document…" to (KeyEvent.VK_N to true), "Dashboard" to (KeyEvent.VK_D to true))
    for (index in 0 until menu.itemCount) {
        val item = menu.getItem(index)
        if (item is java.awt.Menu) installMacShortcuts(item)
        else bindings[item.label]?.let { (key, shift) -> item.shortcut = java.awt.MenuShortcut(key, shift) }
    }
}

@Composable
internal fun FrameWindowScope.MongoDeskMenuBar(state: NativeMenuState, action: (AppCommand) -> Unit) {
    val currentState by rememberUpdatedState(state)
    val currentAction by rememberUpdatedState(action)
    if (isMacDesktop) {
        val context = rememberCompositionContext()
        DisposableEffect(window, context) {
            // AWT creates actual Cocoa menus; no Swing menu row is attached to the macOS window.
            val bar = java.awt.MenuBar()
            val compositions = AppMenuSection.entries.map { section ->
                val menu = java.awt.Menu(section.title)
                bar.add(menu)
                menu.setContent(context) {
                    NativeMenuContents(section, currentState, currentAction)
                    SideEffect { installMacShortcuts(menu) }
                }
            }
            window.menuBar = bar
            onDispose { window.menuBar = null; compositions.forEach { it.dispose() } }
        }
    } else MenuBar {
        AppMenuSection.entries.forEach { section ->
            Menu(section.title, mnemonic = section.mnemonic) { NativeMenuContents(section, state, action) }
        }
    }
}

private enum class AppMenuSection(val title: String, val mnemonic: Char) {
    FILE("File", 'F'),
    EDIT("Edit", 'E'),
    CONNECTION("Connection", 'C'),
    DATABASE("Database", 'D'),
    COLLECTION("Collection", 'L'),
    DOCUMENT("Document", 'O'),
    VIEW("View", 'V'),
    BACKUPS("Backups", 'B'),
    HELP("Help", 'H')
}

@Composable
private fun MenuScope.NativeMenuContents(section: AppMenuSection, state: NativeMenuState, action: (AppCommand) -> Unit) {
    when (section) {
        AppMenuSection.FILE -> {
            Item("New connection…", enabled = state.available, shortcut = shortcut(Key.N)) { action(AppCommand.NEW_CONNECTION) }
            Item("Connections…", enabled = state.available, shortcut = shortcut(Key.K)) { action(AppCommand.CONNECTIONS) }
            Separator()
            Item("Close collection tab", enabled = state.available && !state.dashboard, shortcut = shortcut(Key.W)) { action(AppCommand.CLOSE_TAB) }
            if (!isMacDesktop) {
                Separator()
                Item("Settings…", enabled = state.available, shortcut = shortcut(Key.Comma)) { action(AppCommand.SETTINGS) }
                Separator(); Item("Exit", shortcut = shortcut(Key.Q)) { action(AppCommand.EXIT) }
            }
                }
        AppMenuSection.EDIT -> {
            // Text controls retain their own platform shortcuts; menu clicks dispatch to the focused control.
            Item("Undo", enabled = state.available) { action(AppCommand.UNDO) }
            Item("Redo", enabled = state.available) { action(AppCommand.REDO) }
            Separator()
            Item("Cut", enabled = state.available) { action(AppCommand.CUT) }
            Item("Copy", enabled = state.available) { action(AppCommand.COPY) }
            Item("Paste", enabled = state.available) { action(AppCommand.PASTE) }
            Item("Select all", enabled = state.available) { action(AppCommand.SELECT_ALL) }
                }
        AppMenuSection.CONNECTION -> {
            Item(state.connectionName.ifBlank { "No connection selected" }, enabled = false) {}
            Item("Connections…", enabled = state.available) { action(AppCommand.CONNECTIONS) }
            Item("Disconnect", enabled = state.available && state.connection) { action(AppCommand.DISCONNECT) }
            Separator()
            Menu("Server info", enabled = state.available && state.connection) {
                Item("Server status") { action(AppCommand.SERVER_STATUS) }
                Item("Build info") { action(AppCommand.BUILD_INFO) }
            }
            Item("Export all databases…", enabled = state.available && state.connection) { action(AppCommand.EXPORT_SERVER) }
            Item("Import all databases…", enabled = state.available && state.connection) { action(AppCommand.IMPORT_SERVER) }
                }
        AppMenuSection.DATABASE -> {
            Item(state.databaseName.ifBlank { "Select a database in the sidebar" }, enabled = false) {}
            Item("Create database…", enabled = state.available && state.connection) { action(AppCommand.CREATE_DATABASE) }
            Item("Create collection…", enabled = state.available && state.database) { action(AppCommand.CREATE_COLLECTION) }
            Separator()
            Item("Export database…", enabled = state.available && state.database) { action(AppCommand.EXPORT_DATABASE) }
            Item("Import database…", enabled = state.available && state.database) { action(AppCommand.IMPORT_DATABASE) }
            Item("Schedule database backup…", enabled = state.available && state.database) { action(AppCommand.BACKUP_SCHEDULE) }
            Separator()
            Item("Drop database…", enabled = state.available && state.database) { action(AppCommand.DROP_DATABASE) }
                }
        AppMenuSection.COLLECTION -> {
            Item("Run query / pipeline", enabled = state.available && state.collection, shortcut = shortcut(Key.Enter)) { action(AppCommand.RUN) }
            Item("Refresh results", enabled = state.available && state.collection && state.loaded, shortcut = shortcut(Key.R)) { action(AppCommand.REFRESH) }
            Separator()
            Item("Export collection…", enabled = state.available && state.collection) { action(AppCommand.EXPORT_COLLECTION) }
            Item("Export query result…", enabled = state.available && state.collection && state.loaded) { action(AppCommand.EXPORT_QUERY) }
            Item("Import collection…", enabled = state.available && state.collection) { action(AppCommand.IMPORT_COLLECTION) }
            Separator()
            Item("Drop collection…", enabled = state.available && state.collection) { action(AppCommand.DROP_COLLECTION) }
                }
        AppMenuSection.DOCUMENT -> {
            Item("Create document…", enabled = state.available && state.collection && state.editable, shortcut = shortcut(Key.N, shift = true)) { action(AppCommand.CREATE_DOCUMENT) }
            Item("Inspect document…", enabled = state.available && state.document) { action(AppCommand.INSPECT_DOCUMENT) }
            Item("Edit document…", enabled = state.available && state.document && state.editable) { action(AppCommand.EDIT_DOCUMENT) }
            Menu("Fields and objects", enabled = state.available && state.document && state.editable) {
                Item("Edit field / object…") { action(AppCommand.EDIT_FIELD) }
                Item("Add field…") { action(AppCommand.ADD_FIELD) }
                Item("Delete field…") { action(AppCommand.DELETE_FIELD) }
            }
            Separator()
            Item("Delete document…", enabled = state.available && state.document && state.editable) { action(AppCommand.DELETE_DOCUMENT) }
                }
        AppMenuSection.VIEW -> {
            val appearance = LocalThemeControl.current
            Menu("Appearance") {
                ThemeMode.entries.forEach { mode ->
                    CheckboxItem(if (mode == ThemeMode.SYSTEM) "Follow system" else mode.label, checked = appearance.mode == mode) { appearance.change(mode) }
                }
            }
            Separator()
            Item("Dashboard", enabled = state.available, shortcut = shortcut(Key.D, shift = true)) { action(AppCommand.DASHBOARD) }
            Separator()
            listOf(Triple("Table view", DocumentView.TABLE, AppCommand.TABLE), Triple("Tree view", DocumentView.TREE, AppCommand.TREE), Triple("JSON view", DocumentView.JSON, AppCommand.JSON)).forEach { (label, view, command) ->
                if (isMacDesktop) CheckboxItem(label, checked = !state.dashboard && state.view == view, enabled = state.available && state.collection) { action(command) }
                else RadioButtonItem(label, selected = !state.dashboard && state.view == view, enabled = state.available && state.collection) { action(command) }
            }
                }
        AppMenuSection.BACKUPS -> {
            Item("Manage schedules…", enabled = state.available) { action(AppCommand.BACKUP_MANAGER) }
            Item("Schedule database backup…", enabled = state.available) { action(AppCommand.BACKUP_SCHEDULE) }
                }
        AppMenuSection.HELP -> {
            Item("MongoDesk on GitHub") { action(AppCommand.PROJECT_WEBSITE) }
            if (!isMacDesktop) { Separator(); Item("About MongoDesk", enabled = state.available) { action(AppCommand.ABOUT) } }
                }
    }
}

internal fun FrameWindowScope.sendEditorCommand(command: AppCommand) {
    val key = when (command) { AppCommand.UNDO, AppCommand.REDO -> KeyEvent.VK_Z; AppCommand.CUT -> KeyEvent.VK_X; AppCommand.COPY -> KeyEvent.VK_C; AppCommand.PASTE -> KeyEvent.VK_V; else -> KeyEvent.VK_A }
    val modifiers = (if (isMacDesktop) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK) or (if (command == AppCommand.REDO) KeyEvent.SHIFT_DOWN_MASK else 0)
    EventQueue.invokeLater {
        val focus = window.focusOwner ?: return@invokeLater
        val time = System.currentTimeMillis()
        focus.dispatchEvent(KeyEvent(focus, KeyEvent.KEY_PRESSED, time, modifiers, key, KeyEvent.CHAR_UNDEFINED))
        focus.dispatchEvent(KeyEvent(focus, KeyEvent.KEY_RELEASED, time, modifiers, key, KeyEvent.CHAR_UNDEFINED))
    }
}

@Composable
internal fun NativeApplicationHandlers(about: () -> Unit, settings: () -> Unit, quit: () -> Unit) {
    val currentAbout by rememberUpdatedState(about)
    val currentQuit by rememberUpdatedState(quit)
    val currentSettings by rememberUpdatedState(settings)
    DisposableEffect(Unit) {
        val desktop = if (isMacDesktop && Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop?.isSupported(Desktop.Action.APP_ABOUT) == true) desktop.setAboutHandler { EventQueue.invokeLater { currentAbout() } }
        if (desktop?.isSupported(Desktop.Action.APP_PREFERENCES) == true) desktop.setPreferencesHandler { EventQueue.invokeLater { currentSettings() } }
        if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler { _, response ->
            EventQueue.invokeLater { currentQuit() }; response.cancelQuit()
        }
        onDispose {
            if (desktop?.isSupported(Desktop.Action.APP_ABOUT) == true) desktop.setAboutHandler(null)
            if (desktop?.isSupported(Desktop.Action.APP_PREFERENCES) == true) desktop.setPreferencesHandler(null)
            if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler(null)
        }
    }
}
