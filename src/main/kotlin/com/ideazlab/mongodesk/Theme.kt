package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.jetbrains.skiko.currentSystemTheme
import org.jetbrains.skiko.SystemTheme
import java.nio.file.*

internal enum class ThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("System") }
internal class ThemeStore(private val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk")) {
    fun read(): ThemeMode = runCatching { ThemeMode.valueOf(Files.readString(directory.resolve("appearance.txt")).trim()) }.getOrDefault(ThemeMode.SYSTEM)
    fun save(mode: ThemeMode) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "appearance-", ".tmp")
        try {
            Files.writeString(temp, mode.name)
            try { Files.move(temp, directory.resolve("appearance.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, directory.resolve("appearance.txt"), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
}
/** Native Skiko lookup is fast; Linux desktops may expose their preference through GSettings. */
internal fun detectSystemDark(previous: Boolean = false): Boolean = runCatching {
    when (currentSystemTheme) {
        SystemTheme.DARK -> true
        SystemTheme.LIGHT -> if (System.getProperty("os.name").startsWith("Linux")) linuxDarkPreference() ?: false else false
        else -> if (System.getProperty("os.name").startsWith("Linux")) linuxDarkPreference() ?: previous else previous
    }
}.getOrDefault(previous)

private fun linuxDarkPreference(): Boolean? {
    fun setting(key: String): String? = runCatching {
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", key).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else if (process.exitValue() != 0) null else process.inputStream.bufferedReader().use { it.readText().trim().trim('\'') }
    }.getOrNull()
    return when (setting("color-scheme")) {
        "prefer-dark" -> true
        "prefer-light" -> false
        else -> setting("gtk-theme")?.contains("dark", ignoreCase = true)
    }
}

internal fun ThemeMode.isDark(systemDark: Boolean) = this == ThemeMode.DARK || (this == ThemeMode.SYSTEM && systemDark)
internal val LocalDarkAppearance = staticCompositionLocalOf { false }
internal class ThemeControl(val mode: ThemeMode, val change: (ThemeMode) -> Unit)
internal val LocalThemeControl = staticCompositionLocalOf { ThemeControl(ThemeMode.SYSTEM) {} }
internal object AppColors {
    val action = Color(0xFF0168FA)
    val surface: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF1C2330) else Color(0xFFFFFFFF)
    val ink: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFE3EAF4) else Color(0xFF001737)
    val text: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFD7E1EF) else Color(0xFF1B2E4B)
    val muted: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFA1AFC3) else Color(0xFF8392A5)
    val secondary: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFB0BFD3) else Color(0xFF596882)
    val buttonText: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFCBD7E8) else Color(0xFF3B4863)
    val line: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF364255) else Color(0xFFE3E7ED)
    val pale: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF171D28) else Color(0xFFF5F6FA)
    val subtle: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF202938) else Color(0xFFFAFBFC)
    val header: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF293344) else Color(0xFFF4F5F8)
    val splitter: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF111824) else Color(0xFFF0F2F6)
    val selection: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF223D61) else Color(0xFFEAF2FF)
    val selected: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF2A466D) else Color(0xFFDDEBFF)
    val blue: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF70ACFF) else Color(0xFF0168FA)
    val disabled: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF68788F) else Color(0xFFB4BDCE)
    val danger: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFFF8390) else Color(0xFFDC3545)
    val string: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF8DD6A0) else Color(0xFF28834A)
    val boolean: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFD6A0E4) else Color(0xFF9C3FA6)
    val objectValue: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF69CED6) else Color(0xFF008D9C)
}

@Composable
internal fun MongoDeskTheme(content: @Composable () -> Unit) {
    val store = remember { ThemeStore() }
    var mode by remember { mutableStateOf(store.read()) }
    var systemDark by remember { mutableStateOf(runCatching { currentSystemTheme == SystemTheme.DARK }.getOrDefault(false)) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(mode) {
        if (mode == ThemeMode.SYSTEM) while (isActive) {
            systemDark = withContext(Dispatchers.IO) { detectSystemDark(systemDark) }
            delay(1000)
        }
    }
    val control = ThemeControl(mode) { next ->
        try { store.save(next); mode = next; error = "" }
        catch (_: Exception) { error = "Could not save appearance. Check access to your .mongodesk folder." }
    }
    CompositionLocalProvider(LocalDarkAppearance provides mode.isDark(systemDark), LocalThemeControl provides control) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(minimalHeight = 16.dp, thickness = 8.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), hoverDurationMillis = 150,
                unhoverColor = AppColors.muted.copy(alpha = .45f), hoverColor = AppColors.muted.copy(alpha = .8f)),
            LocalTextSelectionColors provides TextSelectionColors(AppColors.blue, AppColors.selection),
            LocalContextMenuRepresentation provides if (mode.isDark(systemDark)) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
        ) {
            content()
            if (error.isNotEmpty()) androidx.compose.ui.window.DialogWindow(onCloseRequest = { error = "" }, title = "Appearance") {
                Column(Modifier.background(AppColors.surface).padding(24.dp)) { Label(error); Action("Close") { error = "" } }
            }
        }
    }
}
