package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun Dashboard(connections: List<SavedConnection>, pipelines: List<SavedPipeline>, activeName: String?,
    databaseCount: Int, enabled: Boolean, manage: () -> Unit, create: () -> Unit,
    connect: (SavedConnection) -> Unit, pipeline: (SavedPipeline) -> Unit, workspace: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFFFAFBFC)).verticalScroll(rememberScrollState()).padding(28.dp)) {
        Label("DASHBOARD", Color(0xFF8392A5), 10, true)
        Spacer(Modifier.height(8.dp)); Label("Welcome to MongoDesk", size = 26, bold = true)
        Spacer(Modifier.height(10.dp)); Label("Your connections and saved pipelines, ready when you are.", Color(0xFF8392A5), 13)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Saved connections" to connections.size.toString(), "Saved pipelines" to pipelines.size.toString(), "Accessible databases" to databaseCount.toString()).forEach { (label, value) ->
                Column(Modifier.weight(1f).background(Color.White).border(1.dp, WorkspaceLine).padding(18.dp)) {
                    Label(label, Color(0xFF8392A5), 12); Spacer(Modifier.height(10.dp)); Label(value, size = 25, bold = true)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("+ New connection", enabled, primary = true, click = create)
            ToolButton("Manage connections", enabled, click = manage)
            if (activeName != null) ToolButton("Open workspace", enabled, click = workspace)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).border(1.dp, WorkspaceLine).padding(18.dp)) {
            Label("Connections", size = 17, bold = true)
            Spacer(Modifier.height(8.dp)); Label(activeName?.let { "Connected to $it" } ?: "Choose a saved connection to get started.", Color(0xFF8392A5), 12)
            if (connections.isEmpty()) Label("No saved connections yet. Create one or import a backup in the connection manager.", Color(0xFF8392A5), 12, modifier = Modifier.padding(vertical = 14.dp))
            connections.forEach { saved ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(saved.name, bold = true, modifier = Modifier.weight(1f))
                    ToolButton("Connect", enabled) { connect(saved) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().background(Color.White).border(1.dp, WorkspaceLine).padding(18.dp)) {
            Label("Saved pipelines", size = 17, bold = true)
            if (pipelines.isEmpty()) Label("Save a pipeline from the Aggregation tab to reuse it here.", Color(0xFF8392A5), 12, modifier = Modifier.padding(vertical = 14.dp))
            pipelines.forEach { saved ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(saved.name, bold = true)
                        if (saved.database.isNotEmpty()) Label("${saved.database}.${saved.collection}", Color(0xFF8392A5), 11)
                    }
                    ToolButton("Open pipeline", enabled) { pipeline(saved) }
                }
            }
        }
        Spacer(Modifier.height(18.dp)); Label("Dashboard stays pinned and is available every time MongoDesk starts.", Color(0xFF8392A5), 11)
    }
}
