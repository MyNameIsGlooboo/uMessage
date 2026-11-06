package com.glooboo.umessage.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.ui.components.contrastRatio

/** Brand tokens derived from design-system/umessage/MASTER.md (trust navy + gold accent). */
object UMessageColors {
    val Navy = Color(0xFF0F172A)
    val Blue = Color(0xFF1E3A8A)
    val BlueLight = Color(0xFFBFD0FF)
    val Gold = Color(0xFFA16207)
    val GoldLight = Color(0xFFFFDDA8)
    val Background = Color(0xFFF8FAFC)
    val Danger = Color(0xFFDC2626)
    val Success = Color(0xFF0F9D6B)
}

private val Light: ColorScheme = lightColorScheme(
    primary = UMessageColors.Blue,
    onPrimary = Color.White,
    primaryContainer = UMessageColors.BlueLight,
    onPrimaryContainer = UMessageColors.Navy,
    secondary = UMessageColors.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC5D5FA),
    onSecondaryContainer = UMessageColors.Navy,
    tertiary = UMessageColors.Gold,
    onTertiary = Color.White,
    tertiaryContainer = UMessageColors.GoldLight,
    onTertiaryContainer = Color(0xFF3B2600),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    onBackground = Color(0xFF020617),
    onSurface = Color(0xFF020617),
    background = UMessageColors.Background,
    surface = Color.White,
    surfaceVariant = Color(0xFFE8ECF1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFE9EEF5),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFDAE1EB),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF76859B),
    outlineVariant = Color(0xFFE2E8F0),
    error = UMessageColors.Danger,
    onError = Color.White,
    inversePrimary = Color(0xFF9DB6FF),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    surfaceTint = UMessageColors.Blue,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD5DCE6),
    scrim = Color.Black,
).withFixedRoles()

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = UMessageColors.Navy,
    primaryContainer = UMessageColors.Blue,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF9DB6FF),
    onSecondary = UMessageColors.Navy,
    secondaryContainer = Color(0xFF3A4D82),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFFFC970),
    onTertiary = Color(0xFF3B2600),
    tertiaryContainer = Color(0xFF4A3200),
    onTertiaryContainer = Color(0xFFFFDDA8),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    onBackground = Color(0xFFE6EBF5),
    onSurface = Color(0xFFE6EBF5),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2E),
    surfaceVariant = Color(0xFF1C2740),
    surfaceContainerLowest = Color(0xFF070D18),
    surfaceContainerLow = Color(0xFF111A2E),
    surfaceContainer = Color(0xFF18233A),
    surfaceContainerHigh = Color(0xFF22304D),
    surfaceContainerHighest = Color(0xFF2D3C62),
    onSurfaceVariant = Color(0xFFA9B4CC),
    outline = Color(0xFF6B7A99),
    outlineVariant = Color(0xFF34446A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A0A),
    inversePrimary = UMessageColors.Blue,
    inverseSurface = Color(0xFFE6EBF5),
    inverseOnSurface = Color(0xFF111A2E),
    surfaceTint = Color(0xFF9DB6FF),
    surfaceBright = Color(0xFF2A3654),
    surfaceDim = Color(0xFF0B1220),
    scrim = Color.Black,
).withFixedRoles()

/** Fixed accent roles (the same in light and dark), from the brand blue, blue-grey and gold. */
private fun ColorScheme.withFixedRoles() = copy(
    primaryFixed = Color(0xFFDCE6FF),
    primaryFixedDim = UMessageColors.BlueLight,
    onPrimaryFixed = UMessageColors.Navy,
    onPrimaryFixedVariant = UMessageColors.Blue,
    secondaryFixed = Color(0xFFDCE6FF),
    secondaryFixedDim = Color(0xFFBFCBE6),
    onSecondaryFixed = UMessageColors.Navy,
    onSecondaryFixedVariant = Color(0xFF27345C),
    tertiaryFixed = UMessageColors.GoldLight,
    tertiaryFixedDim = Color(0xFFFFC970),
    onTertiaryFixed = Color(0xFF3B2600),
    onTertiaryFixedVariant = Color(0xFF5C3F00),
)

private val UMessageShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Scheme for [choice]; the single place where mode, palette, dynamic colour and OS mode are resolved.
 *
 * WALLPAPER returns the platform's dynamic scheme with its roles kept (only dark cards are lifted, see
 * [withSeparatedCards]). On Android 14+ those roles come from the
 * system's `system_*_light` / `system_*_dark` colours, which follow the user's Settings > Accessibility > Contrast
 * choice: at medium or high contrast the containers turn mid-tone (darker in light mode, lighter in dark mode) and
 * their on-colours turn black or white. That is deliberate and kept, as in Google's own apps; screens must pair every
 * container with its own on-colour (primaryContainer with onPrimaryContainer, and so on) so text stays readable at any
 * contrast. Below Android 12 there is no dynamic colour and WALLPAPER falls back to the brand light and dark schemes.
 */
internal fun resolveColorScheme(
    ctx: Context,
    choice: ThemeChoice,
    darkTheme: Boolean?,
    dynamicColor: Boolean?,
    systemDark: Boolean,
): ColorScheme {
    // SE-44: the mode decides light or dark, the colours decide the palette; previews may force either.
    val dark = darkTheme ?: choice.isDark(systemDark)
    val wallpaper = dynamicColor ?: (choice.colours == ThemeColours.WALLPAPER)
    val scheme = when {
        wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> ThemePresets.palette(choice.colours)?.colorScheme(dark) ?: if (dark) Dark else Light
    }
    return (if (dark && choice.mode == ThemeMode.BLACK) scheme.blackened() else scheme).withSeparatedCards()
}

/** SE-22, VS-35: the least contrast a dark card (surfaceContainerHigh) needs against the page to read as a card. */
internal const val DARK_CARD_CONTRAST = 1.5f

/** surfaceContainerHighest sits one visible step above the cards. */
private const val DARK_HIGHEST_STEP = 1.15f
private const val LIFT_STEP = 0.02f
private const val LIFT_MAX = 0.5f

/**
 * SE-22: dark schemes (the dynamic one in particular) put surfaceContainerHigh about 1.3:1 from the background, so
 * cards melt into the page. Lifts surfaceContainerHigh to [DARK_CARD_CONTRAST] and surfaceContainerHighest one step
 * above it, by mixing in a little onSurface so the hue stays. Light schemes are left as they are.
 */
internal fun ColorScheme.withSeparatedCards(): ColorScheme {
    if (!isDarkBackground) return this
    val high = liftTo(surfaceContainerHigh, DARK_CARD_CONTRAST)
    val highest = liftTo(surfaceContainerHighest, contrastRatio(high, background) * DARK_HIGHEST_STEP)
    return copy(surfaceContainerHigh = high, surfaceContainerHighest = highest)
}

private fun ColorScheme.liftTo(color: Color, target: Float): Color {
    var fraction = 0f
    var lifted = color
    while (contrastRatio(lifted, background) < target && fraction < LIFT_MAX) {
        fraction += LIFT_STEP
        lifted = lerp(color, onSurface, fraction)
    }
    return lifted
}

/** Scheme the app currently uses, for code that runs before any composition (window background, bar icons). */
fun currentColorScheme(ctx: Context): ColorScheme {
    val systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return resolveColorScheme(ctx, ThemePresets.current(ctx), null, null, systemDark)
}

/** Positive status colour (checks, "allowed"): readable as text and as an icon on both light and dark surfaces. */
val ColorScheme.success: Color get() = if (isDarkBackground) Color(0xFF34D399) else Color(0xFF0B7A55)

/** True when text on [this] scheme's background is light, i.e. the system bar icons must be light. */
val ColorScheme.isDarkBackground: Boolean get() = background.luminance() < 0.5f

/**
 * Follows the mode and colours chosen in Settings, observed as state, so a new choice recolours every open screen at
 * once. [darkTheme] and [dynamicColor] stay overridable for previews. WALLPAPER (the default on Android 12+) uses
 * Material You, BRAND the brand light and dark schemes, the rest are palettes with a light and a dark form
 * (see [ThemePresets.palettes]); the mode picks the form, and BLACK turns the dark surfaces black.
 * [syncWindow] keeps the system bar icons and window background in step with the scheme.
 */
@Composable
fun UMessageTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    syncWindow: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val choice = if (LocalInspectionMode.current) {
        ThemeChoice(ThemeMode.SYSTEM, ThemeColours.BRAND)
    } else {
        val chosen by ThemePresets.flow(ctx).collectAsStateWithLifecycle()
        chosen
    }
    val scheme = resolveColorScheme(ctx, choice, darkTheme, dynamicColor, isSystemInDarkTheme())
    if (syncWindow) SyncWindowWithScheme(scheme)
    UMessageFontScale {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(rememberAppFont()),
            shapes = UMessageShapes,
            content = content,
        )
    }
}

/** Keeps system bar icons and the window background in step with [scheme] while the user switches presets live. */
@Composable
private fun SyncWindowWithScheme(scheme: ColorScheme) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val dark = scheme.isDarkBackground
    val background = scheme.background
    LaunchedEffect(view, dark, background) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
    }
}
