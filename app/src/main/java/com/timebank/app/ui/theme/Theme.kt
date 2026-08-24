package com.timebank.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Green = Color(0xFF2E7D32)
private val GreenLight = Color(0xFF81C784)

private val LightColors = lightColorScheme(primary = Green)
private val DarkColors = darkColorScheme(primary = GreenLight)

@Composable
fun TimeBankTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current

    // The app draws edge-to-edge, so the status and navigation bars sit on top of our own
    // background and the system has no way to guess which icon colour will contrast with it.
    // Without this the light theme gets white-on-white: the clock and the balance icon both
    // vanish. "Light bars" means a light *background*, hence dark glyphs — so it's !dark.
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}

/** Compose hands us a themed context wrapper, not the Activity itself. */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
