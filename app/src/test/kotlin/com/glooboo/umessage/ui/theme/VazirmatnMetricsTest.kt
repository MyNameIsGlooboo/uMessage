package com.glooboo.umessage.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The Persian fallback must never make a line taller than its style's line height (see the note in Type.kt): its
 * ascent plus descent has to fit the tightest M3 style, and it matches Roboto's so Persian and English share a baseline.
 */
class VazirmatnMetricsTest {
    private val fontDir = File("src/main/res/font")

    @Test
    fun `vazirmatn fits every line height and matches roboto`() {
        val roboto = metrics(File(fontDir, "roboto_regular.ttf"))
        // Tightest line height to font size ratio in UMessageTypography: bodySmall and labelMedium, 16 / 12.
        val tightest = 16.0 / 12.0
        listOf("vazirmatn_regular.ttf", "vazirmatn_medium.ttf", "vazirmatn_bold.ttf").forEach { name ->
            val vazirmatn = metrics(File(fontDir, name))
            assertTrue("$name is ${vazirmatn.em} em", vazirmatn.em <= tightest)
            assertEquals(name, roboto.copy(typoAscent = 0, typoDescent = 0), vazirmatn.copy(typoAscent = 0, typoDescent = 0))
            assertEquals("$name typo ascent", vazirmatn.ascent, vazirmatn.typoAscent)
            assertEquals("$name typo descent", vazirmatn.descent, vazirmatn.typoDescent)
        }
    }

    private data class Metrics(val unitsPerEm: Int, val ascent: Int, val descent: Int, val typoAscent: Int, val typoDescent: Int) {
        val em: Double get() = (ascent - descent).toDouble() / unitsPerEm
    }

    /** Reads head.unitsPerEm, hhea ascent / descent and OS/2 typo ascent / descent from a TrueType file. */
    private fun metrics(file: File): Metrics {
        val buffer = ByteBuffer.wrap(file.readBytes())
        val tables = (0 until buffer.getShort(4).toInt()).associate { i ->
            val record = 12 + i * 16
            String(ByteArray(4) { buffer.get(record + it) }, Charsets.US_ASCII) to buffer.getInt(record + 8)
        }
        val head = tables.getValue("head")
        val hhea = tables.getValue("hhea")
        val os2 = tables.getValue("OS/2")
        return Metrics(
            unitsPerEm = buffer.getShort(head + 18).toInt(),
            ascent = buffer.getShort(hhea + 4).toInt(),
            descent = buffer.getShort(hhea + 6).toInt(),
            typoAscent = buffer.getShort(os2 + 68).toInt(),
            typoDescent = buffer.getShort(os2 + 70).toInt(),
        )
    }
}
