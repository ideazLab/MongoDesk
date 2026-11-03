package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.bson.*

internal enum class DocumentView(val label: String) { TABLE("Table view"), TREE("Tree view"), JSON("JSON view") }

/** Segmented paths keep dotted, slash-containing and numeric field names unambiguous. */
internal data class TreePath(val document: Int, val segments: List<String> = emptyList())
internal data class DocumentTreeRow(val path: TreePath, val key: String, val value: BsonValue) {
    val root get() = path.segments.isEmpty()
    val depth get() = path.segments.size
    val expandable get() = when (value) {
        is BsonDocument -> value.isNotEmpty()
        is BsonArray -> value.isNotEmpty()
        else -> false
    }
    val summary get() = when (value) {
        is BsonDocument -> "{ ${value.size} fields }"
        is BsonArray -> "[ ${value.size} items ]"
        else -> value.tableValue()
    }
    val type get() = if (root) "Document" else when (value.bsonType) {
        BsonType.DOCUMENT -> "Object"
        BsonType.ARRAY -> "Array"
        BsonType.OBJECT_ID -> "ObjectId"
        BsonType.DATE_TIME -> "Date"
        BsonType.STRING -> "String"
        BsonType.BOOLEAN -> "Bool"
        BsonType.INT32 -> "Int32"
        BsonType.INT64 -> "Int64"
        BsonType.DOUBLE -> "Double"
        BsonType.DECIMAL128 -> "Decimal128"
        BsonType.NULL -> "Null"
        BsonType.BINARY -> "Binary"
        BsonType.REGULAR_EXPRESSION -> "Regex"
        BsonType.TIMESTAMP -> "Timestamp"
        BsonType.JAVASCRIPT -> "JavaScript"
        BsonType.JAVASCRIPT_WITH_SCOPE -> "JavaScript with scope"
        BsonType.SYMBOL -> "Symbol"
        BsonType.DB_POINTER -> "DBPointer"
        BsonType.MIN_KEY -> "MinKey"
        BsonType.MAX_KEY -> "MaxKey"
        BsonType.UNDEFINED -> "Undefined"
        else -> value.bsonType.name
    }
}

internal fun documentTreeRows(documents: List<BsonDocument>, expanded: Set<TreePath>): List<DocumentTreeRow> = buildList {
    val pending = java.util.ArrayDeque<DocumentTreeRow>()
    documents.indices.reversed().forEach { index ->
        val document = documents[index]
        val identity = document["_id"]?.tableValue()?.let { " {_id: $it}" }.orEmpty()
        pending.push(DocumentTreeRow(TreePath(index), "(${index + 1})$identity", document))
    }
    while (pending.isNotEmpty()) {
        val row = pending.pop(); add(row)
        if (row.path !in expanded) continue
        val children = when (val value = row.value) {
            is BsonDocument -> value.entries.map { it.key to it.value }
            is BsonArray -> value.values.mapIndexed { index, child -> index.toString() to child }
            else -> emptyList()
        }
        children.asReversed().forEach { (key, value) ->
            pending.push(DocumentTreeRow(TreePath(row.path.document, row.path.segments + key), key, value))
        }
    }
}

@Composable
internal fun DocumentViewPicker(view: DocumentView, enabled: Boolean, changed: (DocumentView) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val popupOffset = with(LocalDensity.current) { 34.dp.roundToPx() }
    Box {
        ToolButton("${view.label} ▾", enabled) { expanded = !expanded }
        if (expanded) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, popupOffset), onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(160.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(5.dp)) {
                DocumentView.entries.forEach { item ->
                    Box(Modifier.fillMaxWidth().background(if (view == item) AppColors.selection else AppColors.surface)
                        .clickable(role = Role.Button) { expanded = false; changed(item) }.padding(11.dp)) {
                        Label("${if (view == item) "✓ " else ""}${item.label}", if (view == item) WorkspaceBlue else AppColors.text, 12)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentTreeView(documents: List<BsonDocument>, selected: Int?, enabled: Boolean, empty: String,
    expanded: MutableMap<TreePath, Boolean>, selectedPath: TreePath?, pathSelected: (TreePath?) -> Unit,
    select: (Int) -> Unit, inspect: (Int) -> Unit,
    contextMenu: (Int, List<String>) -> List<ContextMenuItem> = { _, _ -> emptyList() }, modifier: Modifier = Modifier) {
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    var keyWidth by remember { mutableStateOf(360f) }
    var valueWidth by remember { mutableStateOf(520f) }
    val rows = documentTreeRows(documents, expanded.filterValues { it }.keys)
    LaunchedEffect(documents) {
        list.scrollToItem(0)
    }
    fun toggle(path: TreePath) { expanded[path] = expanded[path] != true }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.background(AppColors.surface)) {
        val width = maxOf(maxWidth.value, keyWidth + valueWidth + 140f)
        val actualValueWidth = width - keyWidth - 140f
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(AppColors.subtle).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton("Expand documents", enabled && documents.isNotEmpty()) { documents.indices.forEach { expanded[TreePath(it)] = true } }
                ToolButton("Collapse all", enabled && documents.isNotEmpty()) { expanded.clear() }
                Label("${documents.size} documents · Expand objects and arrays to explore fields", AppColors.muted, 11)
            }
            if (documents.isEmpty()) CompactContextMenuArea(items = { contextMenu(-1, emptyList()) }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(empty, AppColors.muted, 13) }
            }
            else Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                    Row(Modifier.width(width.dp).height(30.dp).background(AppColors.header)) {
                        listOf("Key" to keyWidth, "Value" to actualValueWidth, "Type" to 140f).forEachIndexed { index, (title, cellWidth) ->
                            Row(Modifier.width(cellWidth.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                BasicText(title, Modifier.weight(1f).padding(horizontal = 9.dp), style = TextStyle(color = AppColors.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                                Box(Modifier.width(5.dp).fillMaxHeight().background(WorkspaceLine).pointerInput(index, density) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        if (index == 0) keyWidth = (keyWidth + drag.x / density).coerceIn(160f, 1500f)
                                        if (index == 1) valueWidth = (valueWidth + drag.x / density).coerceIn(180f, 1500f)
                                    }
                                })
                            }
                        }
                    }
                    LazyColumn(Modifier.width(width.dp).weight(1f), state = list) {
                        itemsIndexed(rows, key = { _, row -> row.path }) { index, row ->
                            val chosen = selected == row.path.document && (selectedPath == row.path || (row.root && (selectedPath == null || selectedPath.document != selected)))
                            CompactContextMenuArea(items = { contextMenu(row.path.document, row.path.segments) }) {
                            Row(Modifier.fillMaxWidth().height(28.dp).background(if (chosen) AppColors.selected else if (index % 2 == 0) AppColors.surface else AppColors.subtle)
                                .combinedClickable(enabled = enabled, onClick = { pathSelected(row.path); select(row.path.document) }, onDoubleClick = { pathSelected(row.path); select(row.path.document); inspect(row.path.document) })) {
                                Row(Modifier.width(keyWidth.dp).fillMaxHeight().border(.5.dp, WorkspaceLine).padding(start = (row.depth * 18).dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(25.dp).fillMaxHeight().then(if (row.expandable) Modifier.clickable(enabled = enabled, role = Role.Button) { toggle(row.path); pathSelected(row.path); select(row.path.document) } else Modifier), contentAlignment = Alignment.Center) {
                                        if (row.expandable) Label(if (expanded[row.path] == true) "▾" else "▸", AppColors.muted, 13)
                                    }
                                    BasicText(row.key, Modifier.weight(1f).padding(end = 8.dp), style = TextStyle(color = AppColors.text, fontSize = 12.sp, fontWeight = if (row.root) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.width(actualValueWidth.dp).fillMaxHeight().border(.5.dp, WorkspaceLine).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
                                    val color = when { row.value.isString -> AppColors.string; row.value.isBoolean -> AppColors.boolean; row.value.isDocument || row.value.isArray -> AppColors.objectValue; else -> AppColors.text }
                                    BasicText(row.summary, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.width(140.dp).fillMaxHeight().border(.5.dp, WorkspaceLine).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) { Label(row.type, AppColors.muted, 11) }
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
}

@Composable
internal fun PageSizePicker(size: Int, enabled: Boolean, changed: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val offset = with(LocalDensity.current) { 34.dp.roundToPx() }
    Box {
        ToolButton("$size per page ▾", enabled) { expanded = !expanded }
        if (expanded) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset), onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(150.dp).background(AppColors.surface).border(1.dp, WorkspaceLine).padding(5.dp)) {
                MongoSession.PAGE_SIZES.forEach { item ->
                    Box(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = false; if (item != size) changed(item) }.padding(10.dp)) {
                        Label("${if (item == size) "✓ " else ""}$item per page", if (item == size) WorkspaceBlue else AppColors.text, 12)
                    }
                }
            }
        }
    }
}
