package com.pttconnect.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pal {
    val bg = Color(0xFF0D1014)
    val panel = Color(0xFF161B22)
    val line = Color(0xFF2A313C)
    val text = Color(0xFFE9EEF3)
    val muted = Color(0xFF8B96A3)
    val green = Color(0xFF2BB673)   // ready / connected
    val red = Color(0xFFE5484D)     // transmitting
    val amber = Color(0xFFF5A524)   // requesting / reconnecting
    val blue = Color(0xFF3E9BFF)    // receiving
}

@Composable
fun PttTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.green, onPrimary = Color.Black,
            background = Pal.bg, onBackground = Pal.text,
            surface = Pal.panel, onSurface = Pal.text,
            surfaceVariant = Pal.panel, onSurfaceVariant = Pal.muted,
            error = Pal.red, outline = Pal.line,
        ),
        content = content,
    )
}
