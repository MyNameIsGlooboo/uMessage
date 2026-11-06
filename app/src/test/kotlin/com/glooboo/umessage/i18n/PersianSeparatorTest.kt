package com.glooboo.umessage.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N3: in Persian the middle dot "·" next to a number reads as a Persian zero ("هوشمند · ۳" looks like "۳۰"). The
 * Settings and decision-log strings use "،" instead.
 */
class PersianSeparatorTest {

    private val faRes = File("src/main/res/values-fa")

    /** A middle dot with a number or a number placeholder on either side, spaces allowed. */
    private val dotByNumber = Regex("""(\p{Nd}|%(\d+\$)?d)\s*·|·\s*(\p{Nd}|%(\d+\$)?d)""")

    @Test
    fun settingsAndLogStringsHaveNoMiddleDotNextToANumber() {
        val files = faRes.listFiles { f -> f.name.startsWith("strings_settings") || f.name == "strings_log.xml" }
            .orEmpty()
        assertTrue("no Persian settings strings found", files.isNotEmpty())
        val bad = files.flatMap { file ->
            file.readLines()
                .filter { it.contains("<string") && dotByNumber.containsMatchIn(it) }
                .map { "${file.name}: $it" }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun logSeparatorIsNotTheMiddleDot() {
        val log = File(faRes, "strings_log.xml").readText()
        val separator = Regex("""<string name="declog_separator">"(.*)"</string>""").find(log)?.groupValues?.get(1)
        assertTrue("declog_separator: $separator", separator != null && '·' !in separator)
    }
}
