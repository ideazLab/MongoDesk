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
import com.mongodb.ConnectionString
import kotlinx.coroutines.*
import java.net.URLEncoder

internal fun manualConnectionUri(host: String, port: String, srv: Boolean, authenticate: Boolean,
    username: String, password: String, authDatabase: String, tls: Boolean): String {
    val server = host.trim()
    if (server.isBlank() || server.any { it.isWhitespace() || it in "/?@,#" }) throw InputError("Enter a server hostname or IP address.")
    if (!srv && port.toIntOrNull() !in 1..65535) throw InputError("MongoDB port must be between 1 and 65535.")
    if (srv && ':' in server) throw InputError("SRV requires a DNS hostname without a port.")
    if (authenticate && username.isBlank()) throw InputError("Enter the MongoDB username.")
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    val credentials = if (authenticate) "${encode(username)}:${encode(password)}@" else ""
    val address = if (':' in server && !server.startsWith("[")) "[$server]" else server
    val options = mutableListOf("tls=$tls")
    if (authenticate) options += "authSource=${encode(authDatabase.ifBlank { "admin" })}"
    return "${if (srv) "mongodb+srv" else "mongodb"}://$credentials$address${if (srv) "" else ":$port"}/?${options.joinToString("&")}".also {
        try { ConnectionString(it) } catch (_: Exception) { throw InputError("Check the server and authentication fields.") }
    }
}

@Composable
internal fun NewConnectionWizard(profiles: ConnectionProfiles, saved: () -> Unit,
    connected: (ConnectedWorkspace) -> Unit, dismiss: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var section by remember { mutableStateOf("Server") }
    var fromUri by remember { mutableStateOf(true) }
    var uri by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(ConnectionConfig(name = "")) }
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("27017") }
    var srv by remember { mutableStateOf(false) }
    var authenticate by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authDatabase by remember { mutableStateOf("admin") }
    var tls by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tested by remember { mutableStateOf<ConnectionConfig?>(null) }
    val scope = rememberCoroutineScope()
    fun build(): ConnectionConfig = config.copy(uri = if (fromUri) uri.trim() else manualConnectionUri(host, port, srv, authenticate, username, password, authDatabase, tls))
    fun attempt(action: () -> Unit) {
        try { message = ""; action() } catch (e: Exception) { message = if (e is InputError) e.message.orEmpty() else "Check your connection settings." }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = connectionError(e) }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(820.dp).height(690.dp).background(Color.White).border(1.dp, WorkspaceLine).padding(24.dp)) {
            Label("New connection", size = 24, bold = true)
            Spacer(Modifier.height(8.dp)); Label("${step + 1} of 3 · ${listOf("Choose setup method", "Configure connection", "Review and save")[step]}", Color(0xFF8392A5), 12)
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { index -> Box(Modifier.weight(1f).height(3.dp).background(if (index <= step) WorkspaceBlue else WorkspaceLine)) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (step) {
                    0 -> {
                        Label("Start with a connection string or configure a server manually.", size = 14)
                        Spacer(Modifier.height(20.dp))
                        Column(Modifier.selectableGroup()) {
                            RadioOption("Paste a connection string (standard or SRV)", fromUri, !busy) { fromUri = true }
                            Spacer(Modifier.height(8.dp))
                            Editor(uri, { uri = it }, Modifier.fillMaxWidth().height(100.dp), secret = !reveal, enabled = !busy && fromUri)
                            Action(if (reveal) "Hide URI" else "Show URI", enabled = !busy && fromUri) { reveal = !reveal }
                            Spacer(Modifier.height(20.dp))
                            RadioOption("Manually configure connection settings", !fromUri, !busy) { fromUri = false }
                        }
                        Spacer(Modifier.height(12.dp)); Label("URI options and credentials are preserved exactly. Nothing is saved until you finish the wizard.", Color(0xFF8392A5), 12)
                    }
                    1 -> {
                        Field("CONNECTION NAME", config.name, enabled = !busy) { config = config.copy(name = it) }
                        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F6FA))) {
                            listOf("Server", "Authentication", "TLS", "SSH").forEach { name ->
                                Box(Modifier.clickable(enabled = !busy) { section = name }.background(if (section == name) Color(0xFFEAF2FF) else Color.Transparent).padding(14.dp)) {
                                    Label(name, if (section == name) WorkspaceBlue else Color(0xFF8392A5), 12, true)
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        when (section) {
                            "Server" -> {
                                if (fromUri) {
                                    Field("MONGODB URI", uri, secret = !reveal, enabled = !busy) { uri = it }
                                    Action(if (reveal) "Hide URI" else "Show URI", enabled = !busy) { reveal = !reveal }
                                    val parsed = runCatching { ConnectionString(uri.trim()) }.getOrNull()
                                    Spacer(Modifier.height(12.dp)); Label("Server: ${parsed?.hosts?.joinToString() ?: "Check URI"}", size = 12)
                                    Label(if (parsed?.isSrvProtocol == true) "DNS SRV discovery" else "Standard connection string", Color(0xFF8392A5), 12)
                                } else {
                                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        RadioOption("Standard", !srv, !busy) { srv = false }
                                        RadioOption("DNS SRV", srv, !busy) { srv = true; tls = true }
                                    }
                                    Field("SERVER", host, enabled = !busy) { host = it }
                                    if (!srv) Field("PORT", port, enabled = !busy) { port = it }
                                }
                                Spacer(Modifier.height(12.dp))
                                Field("DATABASE (optional; restricts explorer to this database)", config.database, enabled = !busy) { config = config.copy(database = it) }
                            }
                            "Authentication" -> {
                                if (fromUri) {
                                    val parsed = runCatching { ConnectionString(uri.trim()) }.getOrNull()
                                    Label(if (parsed?.credential != null) "Credentials are configured in the connection string." else "No username or password found in the connection string.", size = 13)
                                    Spacer(Modifier.height(12.dp)); Label("Update authentication on the Server tab by editing your URI. All authentication options are retained.", Color(0xFF8392A5), 12)
                                } else {
                                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        RadioOption("No authentication", !authenticate, !busy) { authenticate = false }
                                        RadioOption("Username / password", authenticate, !busy) { authenticate = true }
                                    }
                                    if (authenticate) {
                                        Field("USERNAME", username, enabled = !busy) { username = it }
                                        Field("PASSWORD", password, secret = true, enabled = !busy) { password = it }
                                        Field("AUTHENTICATION DATABASE", authDatabase, enabled = !busy) { authDatabase = it }
                                    }
                                }
                            }
                            "TLS" -> {
                                if (fromUri) {
                                    val parsed = runCatching { ConnectionString(uri.trim()) }.getOrNull()
                                    Label(when (parsed?.sslEnabled) { true -> "TLS enabled"; false -> "TLS disabled"; null -> if (parsed?.isSrvProtocol == true) "TLS enabled by default for SRV" else "TLS uses driver defaults" }, size = 14, bold = true)
                                    Spacer(Modifier.height(12.dp)); Label("TLS options are taken from your URI. Edit tls and certificate options on the Server tab.", Color(0xFF8392A5), 12)
                                } else {
                                    Column(Modifier.selectableGroup()) {
                                        RadioOption("Enable TLS", tls, !busy) { tls = true }
                                        RadioOption("Disable TLS", !tls, !busy) { tls = false }
                                    }
                                    Label("Server certificates and hostnames are verified. For additional TLS options, start with a URI.", Color(0xFF8392A5), 12)
                                }
                            }
                            "SSH" -> {
                                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    RadioOption("Direct", !config.ssh, !busy) { config = config.copy(ssh = false) }
                                    RadioOption("SSH tunnel", config.ssh, !busy) { config = config.copy(ssh = true) }
                                }
                                if (config.ssh) {
                                    Label("SSH requires one standard MongoDB host and a trusted known_hosts entry.", Color(0xFF8392A5), 12)
                                    Spacer(Modifier.height(12.dp))
                                    Field("SSH HOST", config.sshHost, enabled = !busy) { config = config.copy(sshHost = it) }
                                    Field("SSH PORT", config.sshPort, enabled = !busy) { config = config.copy(sshPort = it) }
                                    Field("SSH USERNAME", config.sshUser, enabled = !busy) { config = config.copy(sshUser = it) }
                                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        RadioOption("Private key", !config.passwordAuth, !busy) { config = config.copy(passwordAuth = false, sshSecret = "") }
                                        RadioOption("Password", config.passwordAuth, !busy) { config = config.copy(passwordAuth = true, sshSecret = "") }
                                    }
                                    if (!config.passwordAuth) {
                                        Field("PRIVATE KEY PATH", config.keyFile, enabled = !busy) { config = config.copy(keyFile = it) }
                                        Action("Choose key file", enabled = !busy) { chooseFile()?.let { config = config.copy(keyFile = it) } }
                                    }
                                    Field(if (config.passwordAuth) "SSH PASSWORD" else "KEY PASSPHRASE (optional)", config.sshSecret, secret = true, enabled = !busy) { config = config.copy(sshSecret = it) }
                                    Field("KNOWN_HOSTS FILE", config.knownHosts, enabled = !busy) { config = config.copy(knownHosts = it) }
                                    Action("Choose known_hosts", enabled = !busy) { chooseFile()?.let { config = config.copy(knownHosts = it) } }
                                }
                            }
                        }
                    }
                    2 -> {
                        val final = runCatching { build() }.getOrNull()
                        val parsed = final?.let { runCatching { ConnectionString(it.uri) }.getOrNull() }
                        Label(config.name, size = 21, bold = true); Spacer(Modifier.height(20.dp))
                        Label("Server: ${parsed?.hosts?.joinToString().orEmpty()}", size = 13)
                        Spacer(Modifier.height(12.dp)); Label("Database: ${config.database.ifEmpty { "All accessible databases" }}", size = 13)
                        Spacer(Modifier.height(12.dp)); Label("Authentication: ${if (parsed?.credential != null) "Configured" else "None"}", size = 13)
                        Spacer(Modifier.height(12.dp)); Label("Transport: ${if (config.ssh) "SSH tunnel via ${config.sshHost}" else "Direct"}", size = 13)
                        Spacer(Modifier.height(24.dp)); Label(if (tested == final) "✓ Connection and explorer checks passed." else "Ready to test. You can also save without connecting.", if (tested == final) WorkspaceBlue else Color(0xFF8392A5), 13)
                        Spacer(Modifier.height(12.dp)); Label("Your connection details will be encrypted locally and available after restarting MongoDesk.", Color(0xFF8392A5), 12)
                    }
                }
            }
            Spacer(Modifier.height(12.dp)); Label(if (busy) "Working…" else message, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton("Cancel", !busy) { dismiss() }
                if (step > 0) ToolButton("Back", !busy) { step--; message = "" }
                Spacer(Modifier.weight(1f))
                if (step < 2) ToolButton("Next", !busy, primary = true) { attempt {
                    if (step == 0 && fromUri) {
                        val parsed = try { ConnectionString(uri.trim()) } catch (_: Exception) { throw InputError("Enter a valid mongodb:// or mongodb+srv:// connection string.") }
                        if (config.name.isBlank()) config = config.copy(name = parsed.hosts.firstOrNull().orEmpty())
                    }
                    if (step == 1) { build().validate(); reveal = true }
                    step++
                } } else {
                    ToolButton("Test connection", !busy) { run {
                        val final = build(); final.validate()
                        MongoSession.openWorkspace(final).use { workspace ->
                            tested = final; message = "Connection successful · ${workspace.databases.size} accessible database(s)."
                        }
                    } }
                    ToolButton("Save", !busy) { run {
                        val final = build(); withContext(Dispatchers.IO) { profiles.save(final) }; saved()
                    } }
                    ToolButton("Save & connect", !busy, primary = true) { run {
                        val final = build(); withContext(Dispatchers.IO) { profiles.save(final) }
                        val workspace = MongoSession.openWorkspace(final)
                        try { connected(workspace) } catch (e: Exception) { workspace.close(); throw e }
                    } }
                }
            }
        }
    }
}
