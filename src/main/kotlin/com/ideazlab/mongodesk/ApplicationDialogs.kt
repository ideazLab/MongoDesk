package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.nio.file.Path

private enum class SettingsPage(val title: String, val group: String) {
    APPEARANCE("Appearance", "Appearance and behavior"),
    KEYBOARD("Keyboard shortcuts", "Application"),
    STORAGE("Storage", "Application")
}

@Composable
internal fun SettingsDialog(dismiss: () -> Unit) {
    val theme = LocalThemeControl.current
    var draftTheme by remember { mutableStateOf(theme.mode) }
    var page by remember { mutableStateOf(SettingsPage.APPEARANCE) }
    var search by remember { mutableStateOf("") }
    val matches = SettingsPage.entries.filter { search.isBlank() || (it.title + " " + it.group).contains(search, ignoreCase = true) }
    DialogWindow(onCloseRequest = dismiss, title = "MongoDesk Settings", resizable = false,
        state = rememberDialogState(width = 880.dp, height = 620.dp)) {
        Column(Modifier.fillMaxSize().background(AppColors.surface)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(245.dp).fillMaxHeight().background(AppColors.subtle).border(1.dp, AppColors.line).padding(18.dp)) {
                    Field("Search settings", search) { search = it }
                    matches.groupBy { it.group }.forEach { (group, pages) ->
                        Spacer(Modifier.height(14.dp)); Label(group, AppColors.muted, 11, true)
                        pages.forEach { item ->
                            Label(item.title, if (page == item) AppColors.blue else AppColors.text, 13, page == item,
                                Modifier.fillMaxWidth().background(if (page == item) AppColors.selection else AppColors.subtle)
                                    .clickable { page = item }.padding(horizontal = 12.dp, vertical = 12.dp))
                        }
                    }
                    if (matches.isEmpty()) Label("No matching settings", AppColors.muted, 12)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp)) {
                    Label(page.title, size = 23, bold = true)
                    Spacer(Modifier.height(12.dp))
                    when (page) {
                        SettingsPage.APPEARANCE -> {
                            Label("Choose how MongoDesk looks. Follow system switches automatically with your operating system’s appearance.", AppColors.muted, 13)
                            Spacer(Modifier.height(22.dp)); Label("Theme", size = 14, bold = true)
                            ThemeMode.entries.forEach { mode -> RadioOption(if (mode == ThemeMode.SYSTEM) "Follow system" else mode.label, draftTheme == mode) { draftTheme = mode } }
                            Spacer(Modifier.height(18.dp))
                            Label("Changes take effect when you choose Apply or OK. Your choice is remembered between startups.", AppColors.muted, 12)
                        }
                        SettingsPage.KEYBOARD -> {
                            Label("Shortcuts follow the platform’s modifier key.", AppColors.muted, 13)
                            Spacer(Modifier.height(20.dp))
                            val modifier = if (isMacDesktop) "⌘" else "Ctrl+"
                            listOf("Settings" to "${modifier},", "New connection" to "${modifier}N", "Connection manager" to "${modifier}K",
                                "Close collection tab" to "${modifier}W", "Run query / pipeline" to "${modifier}Enter",
                                "Refresh results" to "${modifier}R", "Create document" to "${modifier}Shift+N",
                                "Dashboard" to "${modifier}Shift+D").forEach { (name, keys) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) { Label(name, modifier = Modifier.weight(1f)); Label(keys, AppColors.muted, 12) }
                            }
                        }
                        SettingsPage.STORAGE -> {
                            Label("MongoDesk keeps your settings and workspace in your user account. These paths are informational.", AppColors.muted, 13)
                            Spacer(Modifier.height(20.dp))
                            val home = Path.of(System.getProperty("user.home"), ".mongodesk")
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("Application data" to home.toString(), "Appearance" to home.resolve("appearance.txt").toString(),
                                        "Workspace" to home.resolve("workspace.json").toString(), "Connection index" to home.resolve("connections.json").toString(),
                                        "Backup schedules" to home.resolve("backups/schedules.json").toString()).forEach { (label, path) ->
                                        Label(label, AppColors.muted, 11, true); Label(path, size = 12)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().border(1.dp, AppColors.line).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Action("Cancel") { dismiss() }
                Action("Apply", enabled = draftTheme != theme.mode) { theme.change(draftTheme) }
                Action("OK", primary = true) { if (draftTheme != theme.mode) theme.change(draftTheme); dismiss() }
            }
        }
    }
}

@Composable
internal fun AboutMongoDeskDialog(dismiss: () -> Unit) {
    var notices by remember { mutableStateOf(false) }
    DialogWindow(onCloseRequest = dismiss, title = "About MongoDesk", resizable = false,
        state = rememberDialogState(width = 720.dp, height = 580.dp)) {
        Column(Modifier.fillMaxSize().background(AppColors.surface).padding(22.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("About", primary = !notices) { notices = false }
                ToolButton("License and notices", primary = notices) { notices = true }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, AppColors.line).padding(24.dp)) {
                if (!notices) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Image(mongoDeskIcon(), "MongoDesk logo", Modifier.size(150.dp))
                    Spacer(Modifier.height(16.dp)); Label("MongoDesk", size = 29, bold = true)
                    Spacer(Modifier.height(8.dp)); Label("Version ${AppBuild.version} · Ideazlab", size = 14)
                    Spacer(Modifier.height(16.dp)); Label("A desktop workspace for MongoDB documents, queries, aggregations and backups.", AppColors.muted, 13)
                    Spacer(Modifier.height(16.dp)); Label("${System.getProperty("os.name")} · ${System.getProperty("os.arch")} · Java ${System.getProperty("java.version")}", AppColors.muted, 11)
                    Spacer(Modifier.height(8.dp)); SelectionContainer { Label("github.com/${AppBuild.repository}", AppColors.blue, 12) }
                } else SelectionContainer(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Label("MongoDesk", size = 20, bold = true)
                        Label("No project license has been added to this build. See the project repository for current licensing information.")
                        Label("Third-party components", size = 15, bold = true)
                        Label("MongoDesk is built with Kotlin, Compose Multiplatform, Skiko, the MongoDB Java/Kotlin driver, Kotlin coroutines, SSHJ, JNA and SLF4J. Each component remains subject to its own license.")
                        Label("MongoDB Database Tools are distributed by MongoDB. Their license files are retained when installed by MongoDesk’s tools installer.")
                        Label("The project repository contains source, build instructions and release information. MongoDesk is an independent application.", AppColors.muted, 12)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Action("OK", primary = true) { dismiss() } }
        }
    }
}
