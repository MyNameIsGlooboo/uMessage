package com.glooboo.umessage.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ThemePresetDarkTest {

    @Test
    fun `every palette has a light and a dark form`() {
        for (preset in ThemePresets.palettes) {
            val light = preset.colorScheme(dark = false)
            val dark = preset.colorScheme(dark = true)
            assertTrue("${preset.id} light background", Tones.tone(light.background.toArgb()) > 90.0)
            assertTrue("${preset.id} dark background", Tones.tone(dark.background.toArgb()) < 12.0)
        }
    }

    @Test
    fun `tones have the requested luminance`() {
        val seed = Tones.hueChroma(0xFF0369A1.toInt())
        for (tone in listOf(4, 10, 20, 30, 40, 50, 60, 80, 90, 95)) {
            val argb = Tones.argb(seed.hue, seed.chroma, tone.toDouble())
            assertEquals("tone $tone", tone.toDouble(), Tones.tone(argb), 0.6)
        }
    }

    @Test
    fun `tones keep the seed hue`() {
        val seed = Tones.hueChroma(0xFFC2410C.toInt())
        val light = Tones.hueChroma(Tones.argb(seed.hue, seed.chroma, 80.0))
        // Compare as unit vectors so wrap-around at +-pi does not matter.
        val drift = abs(cos(seed.hue) - cos(light.hue)) + abs(sin(seed.hue) - sin(light.hue))
        assertTrue("hue drift $drift", drift < 0.15)
    }

    @Test
    fun `generated forms are readable in every role`() {
        for (preset in ThemePresets.palettes) {
            val generated = preset.colorScheme(dark = !preset.dark)
            pairs(generated).forEach { (name, pair) ->
                val ratio = Tones.contrast(pair.first.toArgb(), pair.second.toArgb())
                assertTrue("${preset.id} $name contrast $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `pomegranate keeps white text on its primary readable`() {
        val scheme = ThemePresets.palette(ThemeColours.POMEGRANATE)!!.colorScheme(dark = false)
        assertTrue(Tones.contrast(scheme.onPrimary.toArgb(), scheme.primary.toArgb()) >= 4.5)
        assertTrue(Tones.contrast(scheme.onBackground.toArgb(), scheme.background.toArgb()) >= 7.0)
    }

    @Test
    fun `every palette keeps text and buttons readable in light, dark and black`() {
        for (preset in ThemePresets.palettes) {
            val forms = listOf(
                "light" to preset.colorScheme(dark = false),
                "dark" to preset.colorScheme(dark = true),
                "black" to preset.colorScheme(dark = true).blackened(),
            )
            for ((form, s) in forms) {
                val text = Tones.contrast(s.onBackground.toArgb(), s.background.toArgb())
                val button = Tones.contrast(s.onPrimary.toArgb(), s.primary.toArgb())
                assertTrue("${preset.id} $form text $text", text >= 4.5)
                assertTrue("${preset.id} $form onPrimary $button", button >= 4.5)
            }
        }
    }

    @Test
    fun `every picker colour except wallpaper and brand has a palette`() {
        for (colours in ThemeColours.entries) {
            if (colours == ThemeColours.WALLPAPER || colours == ThemeColours.BRAND) continue
            assertTrue("$colours", ThemePresets.palette(colours) != null)
        }
    }

    @Test
    fun `removed colours map to the nearest remaining palette`() {
        assertEquals(ThemeColours.POMEGRANATE, ThemePresets.coloursFromName("CORAL"))
        assertEquals(ThemeColours.POMEGRANATE, ThemePresets.coloursFromName("SUNSET"))
        assertEquals(ThemeColours.HARBOR, ThemePresets.coloursFromName("FOREST"))
        assertEquals(ThemeColours.INK, ThemePresets.coloursFromName("INK"))
        assertNull(ThemePresets.coloursFromName("MARTIAN"))
        assertNull(ThemePresets.coloursFromName(null))
    }

    @Test
    fun `base form keeps the palette's own colours and generated ones are cached`() {
        for (preset in ThemePresets.palettes) {
            assertEquals(Color(preset.background), preset.colorScheme(preset.dark).background)
            assertNotEquals(preset.colorScheme(false).background, preset.colorScheme(true).background)
            assertSame(preset.colorScheme(!preset.dark), preset.colorScheme(!preset.dark))
        }
    }

    @Test
    fun `black mode turns the page black and keeps cards apart`() {
        val black = ThemePresets.palette(ThemeColours.HARBOR)!!.colorScheme(dark = true).blackened()
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertTrue(Tones.contrast(black.surfaceContainerHigh.toArgb(), black.background.toArgb()) >= 1.3)
    }

    @Test
    fun `mode decides dark independently of the colours`() {
        for (colours in ThemeColours.entries) {
            assertTrue(ThemeChoice(ThemeMode.DARK, colours).isDark(systemDark = false))
            assertTrue(ThemeChoice(ThemeMode.BLACK, colours).isDark(systemDark = false))
            assertTrue(!ThemeChoice(ThemeMode.LIGHT, colours).isDark(systemDark = true))
            assertTrue(ThemeChoice(ThemeMode.SYSTEM, colours).isDark(systemDark = true))
            assertTrue(!ThemeChoice(ThemeMode.SYSTEM, colours).isDark(systemDark = false))
        }
    }

    @Test
    fun `the old single look migrates to a mode and colours`() {
        fun migrate(saved: String?, schema: Int = 2, wallpaper: Boolean = true) =
            ThemePresets.legacyChoice(saved, schema, wallpaper)
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.BRAND), migrate("SYSTEM"))
        assertEquals(ThemeChoice(ThemeMode.LIGHT, ThemeColours.BRAND), migrate("LIGHT"))
        assertEquals(ThemeChoice(ThemeMode.DARK, ThemeColours.BRAND), migrate("DARK"))
        assertEquals(ThemeChoice(ThemeMode.BLACK, ThemeColours.BRAND), migrate("AMOLED"))
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.SKY), migrate("SKY"))
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.POMEGRANATE), migrate("SUNSET"))
        assertEquals(ThemeChoice(ThemeMode.DARK, ThemeColours.HARBOR), migrate("FOREST"))
        assertEquals(ThemeChoice(ThemeMode.DARK, ThemeColours.GRAPHITE), migrate("GRAPHITE"))
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.WALLPAPER), migrate("WALLPAPER"))
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.BRAND), migrate("WALLPAPER", wallpaper = false))
        // Before schema 2 a stored SYSTEM was written on first run, not chosen: the device default applies.
        assertNull(migrate("SYSTEM", schema = 1))
        assertNull(migrate(null))
        assertNull(migrate("NOT_A_PRESET"))
    }

    @Test
    fun `default is wallpaper where the phone has it`() {
        val wallpaper = ThemeChoice(ThemeMode.SYSTEM, ThemeColours.WALLPAPER)
        assertEquals(wallpaper, ThemePresets.defaultChoice(wallpaper = true))
        assertEquals(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.BRAND), ThemePresets.defaultChoice(wallpaper = false))
        assertTrue(ThemeColours.WALLPAPER !in ThemePresets.colourOptions(wallpaper = false))
        assertEquals(ThemeColours.entries.size, ThemePresets.colourOptions(wallpaper = true).size)
    }

    @Test
    fun `colour grid fills rows of three and leaves the rest last`() {
        assertEquals(listOf(3, 3, 1), gridRows(7, 3))
        assertEquals(listOf(3, 3), gridRows(6, 3))
        assertEquals(listOf(2, 2, 2, 1), gridRows(7, 2))
        assertEquals(listOf(2), gridRows(2, 3))
        assertEquals(List(7) { 1 }, gridRows(7, 1))
        assertEquals(emptyList<Int>(), gridRows(0, 3))
        for (count in 1..12) {
            for (columns in 1..6) {
                val rows = gridRows(count, columns)
                assertEquals(count, rows.sum())
                assertTrue("$count in $columns: $rows", rows.dropLast(1).all { it == minOf(columns, count) })
            }
        }
    }

    private fun pairs(s: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
        "primary" to (s.onPrimary to s.primary),
        "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "secondary" to (s.onSecondary to s.secondary),
        "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "tertiary" to (s.onTertiary to s.tertiary),
        "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "error" to (s.onError to s.error),
        "errorContainer" to (s.onErrorContainer to s.errorContainer),
        "background" to (s.onBackground to s.background),
        "surface" to (s.onSurface to s.surface),
        "surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "surfaceVariantText" to (s.onSurfaceVariant to s.surface),
        "surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "primaryOnSurface" to (s.primary to s.surfaceContainerHighest),
        "primaryFixed" to (s.onPrimaryFixed to s.primaryFixed),
        "primaryFixedVariant" to (s.onPrimaryFixedVariant to s.primaryFixed),
        "tertiaryFixed" to (s.onTertiaryFixed to s.tertiaryFixed),
    )
}
