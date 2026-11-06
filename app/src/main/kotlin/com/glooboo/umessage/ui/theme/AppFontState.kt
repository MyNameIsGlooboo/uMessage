package com.glooboo.umessage.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.glooboo.umessage.helpers.AppFont
import com.glooboo.umessage.helpers.W3cPrefs

/** The "Font" setting, followed live; the default font in previews and where preferences are unusable. */
@Composable
fun rememberAppFont(): AppFont {
    if (LocalInspectionMode.current) return AppFont.DEFAULT
    val context = LocalContext.current
    val prefs = remember(context) { runCatching { W3cPrefs.get(context) }.getOrNull() } ?: return AppFont.DEFAULT
    var font by remember(prefs) { mutableStateOf(runCatching { prefs.appFont }.getOrDefault(AppFont.DEFAULT)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            font = runCatching { prefs.appFont }.getOrDefault(AppFont.DEFAULT)
        }
        prefs.registerListener(listener)
        onDispose { prefs.unregisterListener(listener) }
    }
    return font
}
