package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedLineTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val some = SpamStats(blockedTotal = 30, blockedLast7Days = 12, restoredTotal = 0)
    private val none = SpamStats(0, 0, 0)

    @Test
    fun `the line is the level word and the weekly count`() {
        assertEquals(
            "Strict \u00b7 12 stopped this week",
            blockedLineText(resources, some, UnknownSenderMode.STRICT, "12"),
        )
        assertEquals(
            "Smart \u00b7 12 stopped this week",
            blockedLineText(resources, some, UnknownSenderMode.SMART, "12"),
        )
    }

    @Test
    fun `an off level and a zero count keep the line short`() {
        assertEquals(
            "Off \u00b7 Nothing stopped this week",
            blockedLineText(resources, none, UnknownSenderMode.OFF, "0"),
        )
    }

    @Test
    fun `the spoken description names the protection`() {
        assertEquals(
            "Smart protection, 12 stopped this week",
            blockedLineDescription(resources, some, UnknownSenderMode.SMART, "12"),
        )
        assertEquals(
            "Protection off, Nothing stopped this week",
            blockedLineDescription(resources, none, UnknownSenderMode.OFF, "0"),
        )
    }
}
