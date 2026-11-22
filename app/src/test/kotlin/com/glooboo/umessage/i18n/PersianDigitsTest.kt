package com.glooboo.umessage.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.asciiDigits
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.toFolderRow
import com.glooboo.umessage.ui.thread.RelativeDateWords
import com.glooboo.umessage.ui.thread.relativeDateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** In Persian, counts, times and shown numbers use Persian digits (۰-۹); codes and message text are untouched. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fa-w411dp-h891dp")
class PersianDigitsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun conversation(unread: Int) = Conversation(
        threadId = 1, snippet = "کد 482913", date = 0, read = false, title = "+447121234567", photoUri = "",
        isGroupConversation = false, phoneNumber = "+447121234567", unreadCount = unread,
    )

    @Test
    fun `unread badges use Persian digits`() {
        assertEquals("۳", conversation(3).toFolderRow(context, emptyMap(), emptySet()).unreadBadge)
        assertEquals("۹۹+", conversation(120).toFolderRow(context, emptyMap(), emptySet()).unreadBadge)
    }

    @Test
    fun `a number title is shown in Persian digits, while codes and the stored number keep theirs`() {
        // VS-38: the shown title follows the UI language; the number used to reply, call or copy does not change.
        val row = conversation(1).toFolderRow(context, emptyMap(), emptySet())
        assertTrue(row.title, row.title.none { it in '0'..'9' })
        assertEquals("447121234567", asciiDigits(row.title))
        assertEquals("+447121234567", row.phoneNumber)
        assertEquals("کد 482913", row.snippet)
    }

    @Test
    fun `thread date headers use Persian digits for the time`() {
        // 3:21 UTC on the day "now" falls on.
        val at = 3 * 3_600_000L + 21 * 60_000L
        val words = RelativeDateWords("امروز", "دیروز", context.getString(R.string.ui_separator))
        val label = relativeDateLabel(at, at + 60_000L, ZoneOffset.UTC, use24Hour = true, words, context.uiLocale)
        // Recheck 2, P1: the Persian comma, not "·", which reads like a Persian zero next to the digits.
        assertEquals("امروز، ۰۳:۲۱", label)
    }

    @Test
    fun `sizes written in Latin digits are shown in Persian digits`() {
        assertEquals("۲۰۰KB", localizeDigits("200KB", context.uiLocale))
    }
}
