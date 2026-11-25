package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class BlockedLineTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `the line says how many texts went to Spam`() {
        assertEquals(
            "1 text went to Spam in the last 7 days",
            spamWeekLineText(resources, 1, "1"),
        )
        assertEquals(
            "12 texts went to Spam in the last 7 days",
            spamWeekLineText(resources, 12, "12"),
        )
    }

    @Test
    fun `the line shows only with a count, and never on the empty tab`() {
        assertFalse(spamWeekLineShown(0, tabEmpty = false))
        assertFalse(spamWeekLineShown(12, tabEmpty = true))
        assertTrue(spamWeekLineShown(12, tabEmpty = false))
    }
}
