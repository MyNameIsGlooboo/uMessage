package com.glooboo.umessage.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.glooboo.umessage.ui.components.ICON_CONTRAST
import com.glooboo.umessage.ui.components.TEXT_CONTRAST
import com.glooboo.umessage.ui.components.contrastRatio
import com.glooboo.umessage.ui.components.readableOn

private const val STEP = 0.04f
private const val DARK_SURFACE_LUMINANCE = 0.4

/**
 * UI-colours: moves [preferred] toward black or white (whichever the surfaces allow) in small steps until it reaches
 * [minRatio] on every colour of [on]; a colour that already does is returned unchanged, so a palette keeps its look
 * wherever it was fine.
 */
internal fun ensureContrast(preferred: Color, minRatio: Float, on: List<Color>): Color {
    fun worst(c: Color) = on.minOf { contrastRatio(c, it) }
    if (worst(preferred) >= minRatio) return preferred
    val average = on.map { it.luminance() }.average()
    val target = if (average > DARK_SURFACE_LUMINANCE) Color.Black else Color.White
    var fraction = 0f
    var colour = preferred
    while (worst(colour) < minRatio && fraction < 1f) {
        fraction = (fraction + STEP).coerceAtMost(1f)
        colour = lerp(preferred, target, fraction)
    }
    return colour
}

/**
 * UI-colours: raises every text pairing of the scheme to WCAG AA (4.5:1) and the outline (borders of cards and text
 * fields) to the 3:1 non-text minimum on the page and the card colours, in every palette and in both forms. Pairs that
 * already pass are not touched.
 */
internal fun ColorScheme.withGuaranteedContrast(): ColorScheme = copy(
    onPrimary = readableOn(primary, onPrimary),
    onSecondary = readableOn(secondary, onSecondary),
    onTertiary = readableOn(tertiary, onTertiary),
    onError = readableOn(error, onError),
    onPrimaryContainer = readableOn(primaryContainer, onPrimaryContainer),
    onSecondaryContainer = readableOn(secondaryContainer, onSecondaryContainer),
    onTertiaryContainer = readableOn(tertiaryContainer, onTertiaryContainer),
    onErrorContainer = readableOn(errorContainer, onErrorContainer),
    onBackground = readableOn(background, onBackground),
    onSurface = readableOn(surface, onSurface),
    onSurfaceVariant = ensureContrast(onSurfaceVariant, TEXT_CONTRAST, listOf(surface, surfaceVariant, background)),
    outline = ensureContrast(
        outline, ICON_CONTRAST, listOf(background, surface, surfaceContainerLow, surfaceContainer),
    ),
)

/** The star of a starred message: the accent colour, darkened or lightened until it reads on the page (3:1). */
val ColorScheme.starColor: Color
    get() = ensureContrast(tertiary, ICON_CONTRAST, listOf(background, surface))

/**
 * I23: the Contrast palette in light mode is true black on white with a dark outline (Material tone 30), not the
 * generated mid-tone scheme. The accent colours keep the hue of the palette.
 */
@Suppress("MagicNumber") // the fixed hex tones of the Contrast palette are the data here
internal fun ColorScheme.trueBlackOnWhite(): ColorScheme = copy(
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF1F1F1F),
    outline = Color(0xFF474747),
    outlineVariant = Color(0xFF767676),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD9D9D9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
)
