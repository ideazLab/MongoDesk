package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import org.bson.BsonDocument

internal val WorkspaceLine = Color(0xFFE3E7ED)
internal val WorkspaceBlue = Color(0xFF0168FA)

@Composable
internal fun ToolButton(text: String, enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false, click: () -> Unit) {
    val accent = if (danger) Color(0xFFDC3545) else WorkspaceBlue
    Box(Modifier.background(if (primary && enabled) accent else Color.White)
        .border(1.dp, if (primary && enabled) accent else WorkspaceLine)
        .clickable(enabled = enabled, role = Role.Button, onClick = click).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Label(text, if (!enabled) Color(0xFFB4BDCE) else if (primary) Color.White else if (danger) accent else Color(0xFF3B4863), 12)
    }
}

@Composable
internal fun Splitter(vertical: Boolean, moved: (Float) -> Unit) {
    val density = LocalDensity.current.density
    val callback by rememberUpdatedState(moved)
    Box((if (vertical) Modifier.width(6.dp).fillMaxHeight() else Modifier.height(6.dp).fillMaxWidth())
        .background(Color(0xFFF0F2F6)).pointerInput(vertical, density) {
            detectDragGestures { change, drag -> change.consume(); callback((if (vertical) drag.x else drag.y) / density) }
        }, contentAlignment = Alignment.Center) {
        Box((if (vertical) Modifier.width(1.dp).height(36.dp) else Modifier.height(1.dp).width(36.dp)).background(Color(0xFFB4BDCE)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DenseDocumentGrid(documents: List<BsonDocument>, selected: Int?, json: Boolean, enabled: Boolean,
    empty: String, select: (Int) -> Unit, inspect: (Int) -> Unit,
    contextMenu: (Int, List<String>?) -> List<ContextMenuItem> = { _, _ -> emptyList() }, modifier: Modifier = Modifier) {
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    val widths = remember { mutableStateMapOf<String, Float>() }
    val columns = documents.flatMap { it.keys }.distinct()
    fun columnWidth(key: String) = widths[key] ?: if (key == "_id") 210f else if (key.contains("At") || key.contains("Time")) 190f else 170f
    Column(modifier.background(Color.White)) {
        if (documents.isEmpty()) CompactContextMenuArea(items = { contextMenu(-1, null) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(empty, Color(0xFF8392A5), 13) }
        }
        else Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                if (!json) Row(Modifier.background(Color(0xFFF4F5F8)).height(30.dp)) {
                    columns.forEach { key ->
                        Row(Modifier.width(columnWidth(key).dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(key, Modifier.weight(1f).padding(horizontal = 9.dp), style = TextStyle(color = Color(0xFF596882), fontSize = 11.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val density = LocalDensity.current.density
                            Box(Modifier.width(5.dp).fillMaxHeight().background(WorkspaceLine).pointerInput(key, density) {
                                detectDragGestures { change, drag -> change.consume(); widths[key] = (columnWidth(key) + drag.x / density).coerceIn(80f, 700f) }
                            })
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).width(if (json) 1100.dp else columns.sumOf { columnWidth(it).toDouble() }.toFloat().dp), state = list) {
                    itemsIndexed(documents) { index, document ->
                        val background = if (selected == index) Color(0xFFDDEBFF) else if (index % 2 == 0) Color.White else Color(0xFFFAFBFC)
                        val rowModifier = Modifier.fillMaxWidth().background(background)
                            .combinedClickable(enabled = enabled, onClick = { select(index) }, onDoubleClick = { select(index); inspect(index) })
                        if (json) CompactContextMenuArea(items = { contextMenu(index, null) }) {
                            Box(rowModifier.padding(12.dp)) {
                                BasicText(document.pretty(), style = TextStyle(color = Color(0xFF001737), fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                            }
                        } else Row(rowModifier.height(28.dp)) {
                            columns.forEach { key ->
                                CompactContextMenuArea(items = { contextMenu(index, listOf(key)) }) {
                                Box(Modifier.width(columnWidth(key).dp).fillMaxHeight().border(.5.dp, WorkspaceLine).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
                                    BasicText(document[key]?.tableValue() ?: "—", style = TextStyle(color = Color(0xFF1B2E4B), fontSize = 12.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.fillMaxWidth().height(10.dp))
    }
}

internal data class DocumentInspection(val document: BsonDocument, val context: String, val target: DocumentEditTarget?)

@Composable
internal fun DocumentInspectorDialog(inspection: DocumentInspection, edit: (DocumentEditTarget) -> Unit,
    delete: (DocumentEditTarget) -> Unit, dismiss: () -> Unit) {
    DialogWindow(onCloseRequest = dismiss, title = "MongoDesk · Document inspector", state = rememberDialogState(width = 900.dp, height = 740.dp)) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(22.dp)) {
            Label(if (inspection.target == null) "Inspect aggregation result" else "Inspect document", size = 21, bold = true)
            Spacer(Modifier.height(8.dp)); Label(inspection.context, Color(0xFF8392A5), 12)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFFAFBFC)).border(1.dp, WorkspaceLine).padding(14.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    BasicText(inspection.document.pretty(), style = TextStyle(color = Color(0xFF001737), fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Close") { dismiss() }
                inspection.target?.let { target ->
                    Action("Edit document", primary = true) { edit(target) }
                    Action("Delete document", danger = true) { delete(target) }
                }
            }
        }
    }
}
