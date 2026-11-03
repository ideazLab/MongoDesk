package com.ideazlab.mongodesk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap

@Composable
internal fun mongoDeskIcon(): Painter {
    val dark = LocalDarkAppearance.current
    return remember(dark) {
        val resource = if (dark) "/branding/mongodesk-icon-dark.png" else "/branding/mongodesk-icon.png"
        BitmapPainter(checkNotNull(object {}.javaClass.getResourceAsStream(resource)) {
            "The MongoDesk icon resource is missing."
        }.use { loadImageBitmap(it) })
    }
}
