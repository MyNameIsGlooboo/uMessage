package com.glooboo.umessage.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import com.glooboo.umessage.helpers.TextSize
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.helpers.appFontScale

/** True below a [UMessageFontScale], so a nested [UMessageTheme] (dialogs, previews) never scales twice. */
@Suppress("CompositionLocalAllowlist") // Private flag read only here; no other code can provide or consume it.
private val LocalFontScaleApplied = staticCompositionLocalOf { false }

/**
 * Applies the "Text size" setting (ID-36): every `sp` below this point, and so every [androidx.compose.material3.Typography]
 * style, is multiplied by the chosen size on top of the system font scale. Follows the preference live, so the Settings
 * screen itself grows and shrinks as the slider moves.
 */
@Composable
fun UMessageFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val applied = LocalFontScaleApplied.current
    val scaled = rememberAppFontDensity(density, applied)
    // Where nothing is scaled this provides the values already in place, so [content] sits in one slot either way.
    CompositionLocalProvider(
        LocalDensity provides (scaled ?: density),
        LocalFontScaleApplied provides (applied || scaled != null),
        content = content,
    )
}

/** [density] with the "Text size" setting applied, or null where it does not apply (previews, nested, no prefs). */
@Composable
private fun rememberAppFontDensity(density: Density, alreadyApplied: Boolean): Density? {
    if (LocalInspectionMode.current || alreadyApplied) return null
    val context = LocalContext.current
    // Tests and tools without usable preferences fall back to the default size (1.0).
    val prefs = remember(context) { runCatching { W3cPrefs.get(context) }.getOrNull() } ?: return null
    var size by remember(prefs) { mutableStateOf(runCatching { prefs.textSize }.getOrDefault(TextSize.DEFAULT)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            size = runCatching { prefs.textSize }.getOrDefault(TextSize.DEFAULT)
        }
        prefs.registerListener(listener)
        onDispose { prefs.unregisterListener(listener) }
    }
    return remember(density.density, density.fontScale, size) {
        Density(density.density, appFontScale(density.fontScale, size))
    }
}
