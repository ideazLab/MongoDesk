package com.ideazlab.mongodesk

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

internal class SubmenuContextItem(label: String, val children: List<ContextMenuItem>) : ContextMenuItem(label, {})

@Composable
internal fun CompactContextMenuArea(items: () -> List<ContextMenuItem>, fontSize: Int = 12, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val representation = remember(fontSize) { CompactContextMenu(fontSize) }
    Box(modifier) {
        CompositionLocalProvider(LocalContextMenuRepresentation provides representation) { ContextMenuArea(items = items, content = content) }
    }
}

@Composable
private fun CompactMenuColumn(entries: List<ContextMenuItem>, selected: Int, fontSize: Int, hover: (Int) -> Unit, choose: (Int) -> Unit) {
    Column(Modifier.widthIn(min = 130.dp, max = 320.dp).width(IntrinsicSize.Max).shadow(7.dp).background(Color.White).border(1.dp, WorkspaceLine).padding(4.dp)) {
        entries.forEachIndexed { index, item ->
            val interaction = remember(item.label) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            LaunchedEffect(hovered) { if (hovered) hover(index) }
            Row(Modifier.fillMaxWidth().background(if (hovered || selected == index) Color(0xFFEAF2FF) else Color.White)
                .hoverable(interaction).clickable(role = Role.Button) { choose(index) }.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(item.label, Color(0xFF1B2E4B), size = fontSize, modifier = Modifier.weight(1f))
                if (item is SubmenuContextItem) { Spacer(Modifier.width(14.dp)); Label("›", Color(0xFF8392A5), size = fontSize) }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private class CompactContextMenu(private val fontSize: Int) : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val open = state.status as? ContextMenuState.Status.Open ?: return
        val entries = items()
        if (entries.isEmpty()) return
        var selected by remember(open) { mutableStateOf(-1) }
        var submenu by remember(open) { mutableStateOf<Int?>(null) }
        var childSelected by remember(open) { mutableStateOf(-1) }
        var childFocus by remember(open) { mutableStateOf(false) }
        val children = (submenu?.let { entries.getOrNull(it) } as? SubmenuContextItem)?.children.orEmpty()
        val focus = remember { FocusRequester() }
        fun dismiss() { state.status = ContextMenuState.Status.Closed }
        fun chooseParent(index: Int) {
            val item = entries.getOrNull(index) ?: return
            if (item is SubmenuContextItem) { selected = index; submenu = index; childSelected = -1; childFocus = false }
            else { dismiss(); item.onClick() }
        }
        fun chooseChild(index: Int) { children.getOrNull(index)?.let { dismiss(); it.onClick() } }
        Popup(popupPositionProvider = rememberPopupPositionProviderAtPosition(open.rect.center),
            onDismissRequest = { dismiss() }, properties = PopupProperties(focusable = true)) {
            Row(Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionDown -> {
                        if (childFocus) childSelected = (childSelected + 1).coerceAtMost(children.lastIndex)
                        else { selected = (selected + 1).coerceAtMost(entries.lastIndex); submenu = null }
                        true
                    }
                    Key.DirectionUp -> {
                        if (childFocus) childSelected = if (childSelected < 0) children.lastIndex else (childSelected - 1).coerceAtLeast(0)
                        else { selected = if (selected < 0) entries.lastIndex else (selected - 1).coerceAtLeast(0); submenu = null }
                        true
                    }
                    Key.DirectionRight -> {
                        val index = if (selected < 0) 0 else selected
                        if (entries.getOrNull(index) is SubmenuContextItem) { chooseParent(index); childFocus = true; childSelected = 0 }; true
                    }
                    Key.DirectionLeft -> { submenu = null; childFocus = false; true }
                    Key.Enter, Key.NumPadEnter -> {
                        if (childFocus) chooseChild(if (childSelected < 0) 0 else childSelected)
                        else {
                            val index = if (selected < 0) 0 else selected
                            chooseParent(index)
                            if (entries.getOrNull(index) is SubmenuContextItem) { childFocus = true; childSelected = 0 }
                        }
                        true
                    }
                    Key.Escape -> { dismiss(); true }
                    else -> false
                }
            }.focusable(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                CompactMenuColumn(entries, selected, fontSize, hover = { index ->
                    selected = index; childFocus = false; childSelected = -1
                    submenu = index.takeIf { entries[it] is SubmenuContextItem }
                }, choose = { chooseParent(it) })
                if (children.isNotEmpty()) CompactMenuColumn(children, childSelected, fontSize,
                    hover = { childSelected = it; childFocus = true }, choose = { chooseChild(it) })
            }
            LaunchedEffect(open) { focus.requestFocus() }
        }
    }
}
