package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun AggregationBuilder(
    stages: List<PipelineStage>, onStagesChange: (List<PipelineStage>) -> Unit,
    rawJson: String, onJsonChange: (String) -> Unit,
    jsonMode: Boolean, onModeChange: (Boolean) -> Unit,
    enabled: Boolean, error: (String) -> Unit, bodyHeight: Dp = 220.dp
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    val selected = stages.firstOrNull { it.id == selectedId } ?: stages.firstOrNull()
    fun update(stage: PipelineStage) { onStagesChange(stages.map { if (it.id == stage.id) stage else it }) }
    fun move(direction: Int) {
        if (selected == null) return
        val index = stages.indexOf(selected)
        val target = index + direction
        if (target !in stages.indices) return
        onStagesChange(stages.toMutableList().apply { add(target, removeAt(index)) })
        selectedId = selected.id
    }
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(if (jsonMode) "Stage builder" else "Pipeline JSON", enabled = enabled) {
                try {
                    if (jsonMode) {
                        val builderJson = runCatching { pipelineToJson(compileStages(stages)) }.getOrNull()
                        if (rawJson != builderJson) onStagesChange(stagesFromPipeline(rawJson))
                        onModeChange(false)
                    } else {
                        val json = pipelineToJson(compileStages(stages))
                        onJsonChange(json); onModeChange(true)
                    }
                } catch (e: InputError) { error(e.message.orEmpty()) }
            }
            if (!jsonMode) Box {
                Action("+ Add stage", enabled = enabled) { showTemplates = !showTemplates }
                if (showTemplates) Popup(alignment = Alignment.TopStart, onDismissRequest = { showTemplates = false }, properties = PopupProperties(focusable = true)) {
                    LazyColumn(Modifier.width(220.dp).heightIn(max = 300.dp).background(Color.White)
                        .border(1.dp, Color(0xFFE3E7ED)).padding(6.dp)) {
                        items(stageTemplates.entries.toList(), key = { it.key }) { template ->
                            Label(template.key, modifier = Modifier.fillMaxWidth().clickable {
                                val stage = PipelineStage(template.key, template.value)
                                onStagesChange(stages + stage); selectedId = stage.id; showTemplates = false
                            }.padding(10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Label("${if (jsonMode) "Extended JSON" else "${stages.count { it.enabled }} enabled stages"} · Read-only", Color(0xFF8392A5), 11)
        }
        Spacer(Modifier.height(10.dp))
        if (jsonMode) Box(Modifier.fillMaxWidth().height(bodyHeight).verticalScroll(rememberScrollState())) {
            Editor(rawJson, onJsonChange, Modifier.fillMaxWidth().heightIn(min = bodyHeight), enabled = enabled)
        } else Row(Modifier.fillMaxWidth().height(bodyHeight).border(1.dp, Color(0xFFE3E7ED))) {
            LazyColumn(Modifier.width(180.dp).fillMaxHeight().background(Color(0xFFFAFBFC))) {
                items(stages, key = { it.id }) { stage ->
                    val index = stages.indexOf(stage)
                    Label("${index + 1}. ${if (stage.enabled) "●" else "○"} ${stage.operator}",
                        if (stage.id == selected?.id) Color(0xFF0168FA) else Color(0xFF8392A5),
                        modifier = Modifier.fillMaxWidth().background(if (stage.id == selected?.id) Color(0xFFEAF2FF) else Color.Transparent)
                            .clickable(enabled = enabled) { selectedId = stage.id }.padding(12.dp))
                }
                if (stages.isEmpty()) item { Label("Add a stage to begin. An empty pipeline returns documents.", Color(0xFF8392A5), 12, modifier = Modifier.padding(12.dp)) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                if (selected != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Editor(selected.operator, { update(selected.copy(operator = it)) }, Modifier.width(140.dp).height(40.dp), enabled = enabled)
                        Action(if (selected.enabled) "Enabled" else "Disabled", enabled = enabled) { update(selected.copy(enabled = !selected.enabled)) }
                        Action("↑", enabled = enabled && stages.indexOf(selected) > 0) { move(-1) }
                        Action("↓", enabled = enabled && stages.indexOf(selected) < stages.lastIndex) { move(1) }
                        Action("Remove", enabled = enabled) { onStagesChange(stages.filterNot { it.id == selected.id }); selectedId = null }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Editor(selected.body, { update(selected.copy(body = it)) }, Modifier.fillMaxWidth().heightIn(min = 140.dp), enabled = enabled)
                    }
                } else Label("Choose + Add stage or switch to Pipeline JSON.", Color(0xFF8392A5), 12)
            }
        }
    }
}
