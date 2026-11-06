package com.glooboo.umessage.ui.theme

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb

/**
 * Edge-to-edge with bar icons that follow the app's own theme (a dark preset on a light phone needs light
 * icons) and a window background equal to the screen background, so no frame of the wrong colour shows
 * while the first composition is built. Call before `setContent`, and after `super.onCreate` for activities
 * whose base class sets up its own window.
 */
fun ComponentActivity.applyUMessageWindow() {
    val scheme = currentColorScheme(this)
    val dark = scheme.isDarkBackground
    val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
    // 3-button navigation gets a solid bar in the page colour instead of the translucent gradient scrim, so content
    // never reads under the buttons and controls near the bottom are not faded. Gesture navigation stays transparent.
    val solid = scheme.background.toArgb()
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = SystemBarStyle.auto(solid, solid) { dark })
    window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
}
