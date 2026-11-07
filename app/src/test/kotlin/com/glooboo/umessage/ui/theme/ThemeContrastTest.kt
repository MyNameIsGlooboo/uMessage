package com.glooboo.umessage.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.components.ICON_CONTRAST
import com.glooboo.umessage.ui.components.TEXT_CONTRAST
import com.glooboo.umessage.ui.components.contrastRatio
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.labels.LabelColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** UI-colours: text pairs reach WCAG AA (4.5:1) and borders and stars 3:1 in every palette, light and dark. */
@RunWith(AndroidJUnit4::class)
class ThemeContrastTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val forms = listOf(
        ThemeMode.LIGHT to false,
        ThemeMode.DARK to true,
        ThemeMode.BLACK to true,
    )

    private fun schemes() = ThemeColours.entries.filter { it != ThemeColours.WALLPAPER }.flatMap { colours ->
        forms.map { (mode, dark) ->
            Triple("$colours/$mode", resolveColorScheme(context, ThemeChoice(mode, colours), dark, false, false), dark)
        }
    }

    private fun check(name: String, what: String, a: Color, b: Color, min: Float) {
        val ratio = contrastRatio(a, b)
        assertTrue("$name: $what is $ratio, needs $min", ratio >= min)
    }

    @Test
    fun `every text pair of every palette in light and dark reaches 4_5 to 1`() {
        schemes().forEach { (name, s, _) ->
            check(name, "onPrimary/primary", s.onPrimary, s.primary, TEXT_CONTRAST)
            check(name, "onSecondary/secondary", s.onSecondary, s.secondary, TEXT_CONTRAST)
            // The search highlight is onTertiary on tertiary.
            check(name, "onTertiary/tertiary", s.onTertiary, s.tertiary, TEXT_CONTRAST)
            check(name, "onError/error", s.onError, s.error, TEXT_CONTRAST)
            check(name, "onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, TEXT_CONTRAST)
            check(name, "onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, TEXT_CONTRAST)
            check(name, "onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, TEXT_CONTRAST)
            check(name, "onBackground", s.onBackground, s.background, TEXT_CONTRAST)
            check(name, "onSurface", s.onSurface, s.surface, TEXT_CONTRAST)
            check(name, "onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, TEXT_CONTRAST)
            check(name, "onSurfaceVariant/variant", s.onSurfaceVariant, s.surfaceVariant, TEXT_CONTRAST)
        }
    }

    @Test
    fun `borders and the star reach 3 to 1 on the page and on cards`() {
        schemes().forEach { (name, s, _) ->
            check(name, "outline/background", s.outline, s.background, ICON_CONTRAST)
            check(name, "outline/surface", s.outline, s.surface, ICON_CONTRAST)
            check(name, "outline/container", s.outline, s.surfaceContainer, ICON_CONTRAST)
            check(name, "star/background", s.starColor, s.background, ICON_CONTRAST)
            check(name, "star/surface", s.starColor, s.surface, ICON_CONTRAST)
        }
    }

    @Test
    fun `the guard leaves a pair that already passes exactly as it was`() {
        val s = resolveColorScheme(context, ThemeChoice(ThemeMode.LIGHT, ThemeColours.BRAND), false, false, false)
        // Brand's white on blue already passes: it keeps its own colour.
        assertEquals(Color.White, s.onPrimary)
    }

    @Test
    fun `Contrast in light mode is true black on white`() {
        val s = resolveColorScheme(context, ThemeChoice(ThemeMode.LIGHT, ThemeColours.CONTRAST), false, false, false)
        assertEquals(Color.White, s.background)
        assertEquals(Color.Black, s.onBackground)
        assertEquals(Color.White, s.surface)
        assertEquals(Color.Black, s.onSurface)
        check("CONTRAST/light", "outline on white", s.outline, s.background, 7f)
    }

    @Test
    fun `the selected tick reads on every label colour`() {
        LabelColor.entries.forEach {
            val swatch = Color(it.color)
            check(it.name, "tick", readableOn(swatch, Color.White, ICON_CONTRAST), swatch, ICON_CONTRAST)
        }
    }
}
