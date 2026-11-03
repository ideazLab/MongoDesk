package com.ideazlab.mongodesk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap

@Composable
internal fun mongoDeskIcon(): Painter = remember {
    BitmapPainter(checkNotNull(object {}.javaClass.getResourceAsStream("/branding/mongodesk-icon.png")) {
        "The MongoDesk icon resource is missing."
    }.use { loadImageBitmap(it) })
}
