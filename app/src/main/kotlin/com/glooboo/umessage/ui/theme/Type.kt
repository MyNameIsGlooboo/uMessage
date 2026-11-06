package com.glooboo.umessage.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.FontRes
import androidx.annotation.RequiresApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.AppFont

/**
 * Google Sans Flex (headings, titles, buttons; SIL OFL 1.1) + Roboto (body; Apache 2.0), as Google's own apps pair them.
 * Bundled as static weight instances: HyperOS ignores the wght axis of variable fonts and renders them extra light.
 * Sizes follow the Material 3 type scale; body tracking is trimmed to 0.1 sp (VS-26, VS-37) because the wide M3 tracking
 * made Latin text look looser next to Vazirmatn, whose Arabic-script glyphs are tracked as written.
 *
 * Neither font has Arabic-script glyphs, so on Android 10+ each weight carries Vazirmatn (SIL OFL 1.1) as its first
 * fallback: Persian and Arabic characters in any text, mixed or not, are drawn with Vazirmatn instead of whatever font
 * the device falls back to. Android 8 and 9 have no custom fallback API and keep the system fallback.
 *
 * The bundled Vazirmatn files carry Roboto's vertical metrics (ascent 1900, descent -500 on 2048 units, hhea and
 * OS/2 typo) instead of upstream's 2100 / -1100. Android extends a line's ascent and descent to those of any fallback
 * font used on it, and upstream's 1.56 em is taller than every M3 line height here (bodyMedium is 20/14 = 1.43 em).
 * Compose then shrinks the line to the style's line height but adds the shrunk amount back as padding above and below
 * the text so the glyphs are not clipped, which made a Persian row taller than an English one (57 vs 53 px for
 * bodyMedium at 420 dpi). With Roboto's 1.17 em the fallback never exceeds the line height, so Persian and English lines
 * are exactly the style's line height and share a baseline. Glyph outlines are unchanged; [VazirmatnMetricsTest]
 * guards the values.
 */
val GoogleSansFamily = FontFamily(
    font(R.font.google_sans_flex_regular, R.font.vazirmatn_regular, FontWeight.Normal),
    font(R.font.google_sans_flex_medium, R.font.vazirmatn_medium, FontWeight.Medium),
    font(R.font.google_sans_flex_semibold, R.font.vazirmatn_bold, FontWeight.SemiBold),
    font(R.font.google_sans_flex_bold, R.font.vazirmatn_bold, FontWeight.Bold),
)

val RobotoFamily = FontFamily(
    font(R.font.roboto_regular, R.font.vazirmatn_regular, FontWeight.Normal),
    font(R.font.roboto_medium, R.font.vazirmatn_medium, FontWeight.Medium),
    font(R.font.roboto_bold, R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun font(@FontRes primary: Int, @FontRes arabic: Int, weight: FontWeight): Font =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) FallbackFont(primary, arabic, weight) else Font(primary, weight)

/** A bundled font with a second bundled font as its first fallback, ahead of the system fallback. */
@RequiresApi(Build.VERSION_CODES.Q)
private data class FallbackFont(
    @FontRes val primary: Int,
    @FontRes val fallback: Int,
    override val weight: FontWeight,
) : AndroidFont(FontLoadingStrategy.Blocking, FallbackTypefaceLoader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal
}

@RequiresApi(Build.VERSION_CODES.Q)
private object FallbackTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        font as FallbackFont
        fun family(@FontRes id: Int) = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(context.resources, id).build(),
        ).build()
        val style = android.graphics.fonts.FontStyle(font.weight.weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
        return Typeface.CustomFallbackBuilder(family(font.primary))
            .addCustomFallback(family(font.fallback))
            .setStyle(style)
            .setSystemFallback("sans-serif")
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
}

/**
 * Lines keep their full line height with the text centred in it, as in Material 3's own type tokens. Without this,
 * Compose trims the space above the first line and below the last, so text sits tighter than spec and a line's height
 * depends on its font's metrics (see the Vazirmatn note above for the other half of keeping Persian lines the same).
 */
private val CenteredLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun heading(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = GoogleSansFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = CenteredLineHeight,
)

private fun body(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = RobotoFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = CenteredLineHeight,
)

val UMessageTypography = Typography(
    displayLarge = heading(57, 64, FontWeight.Normal, -0.25f),
    displayMedium = heading(45, 52, FontWeight.Normal),
    displaySmall = heading(36, 44, FontWeight.Normal),
    headlineLarge = heading(32, 40, FontWeight.Normal),
    headlineMedium = heading(28, 36, FontWeight.Normal),
    headlineSmall = heading(24, 32, FontWeight.Normal),
    titleLarge = heading(22, 28, FontWeight.Normal),
    titleMedium = heading(16, 24, FontWeight.Medium, 0.1f),
    titleSmall = heading(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = body(16, 24, FontWeight.Normal, 0.1f),
    bodyMedium = body(14, 20, FontWeight.Normal, 0.1f),
    bodySmall = body(12, 16, FontWeight.Normal, 0.2f),
    labelLarge = heading(14, 20, FontWeight.Medium, 0.1f),
    labelMedium = heading(12, 16, FontWeight.Medium, 0.3f),
    labelSmall = heading(11, 16, FontWeight.Medium, 0.3f),
)

/** [UMessageTypography] with every style drawn in [family]; sizes, weights, spacing and line heights are kept. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/** The phone's own sans-serif for every script: Persian and Arabic come out in the system's Arabic-script font. */
private val SystemTypography: Typography by lazy { UMessageTypography.withFamily(FontFamily.Default) }

/** The typography for the "Font" setting; both values are built once. */
fun typographyFor(font: AppFont): Typography = when (font) {
    AppFont.UMESSAGE -> UMessageTypography
    AppFont.SYSTEM -> SystemTypography
}

/**
 * Text direction follows the first strong character of the text itself, so Persian and Arabic messages read and align
 * right-to-left even in an English UI, while neutral text (numbers, symbols) stays left-to-right.
 */
fun TextStyle.contentDirection(): TextStyle = copy(textDirection = TextDirection.ContentOrLtr)
