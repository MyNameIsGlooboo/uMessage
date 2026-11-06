package com.glooboo.umessage.ui.spam

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.BankCardPrefs
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.forwarded.ForwardedUiState
import com.glooboo.umessage.ui.forwarded.StatusCard
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class W7dUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) { content() }
    }

    @Test
    fun `the forwarding switch is on while forwarding runs and off when paused`() {
        val rules = listOf(ForwardRule(id = 1, targets = listOf("+15550100")))
        show { StatusCard(ForwardedUiState(loading = false, rules = rules, paused = false), {}, {}) }
        compose.onNodeWithContentDescription("Forwarding").assertIsOn()
    }

    @Test
    fun `a paused forwarding switch is off and turning it on resumes`() {
        val changes = mutableListOf<Boolean>()
        show { StatusCard(ForwardedUiState(loading = false, paused = true), { changes += it }, {}) }
        compose.onNodeWithContentDescription("Forwarding").assertIsOff().performClick()
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `the blocked line shows the level word, the count and opens the protection page`() {
        var opened = 0
        show {
            BlockedLine(
                stats = SpamStats(blockedTotal = 30, blockedLast7Days = 12, restoredTotal = 0),
                mode = UnknownSenderMode.SMART,
                onOpenSettings = { opened++ },
            )
        }
        compose.onNodeWithText("Smart \u00B7 12 stopped this week").assertExists().performClick()
        assertEquals(1, opened)
        compose.onNodeWithContentDescription("Smart protection, 12 stopped this week").assertExists()
    }

    @Test
    fun `the blocked line says when nothing was stopped`() {
        show { BlockedLine(SpamStats(0, 0, 0), UnknownSenderMode.OFF, onOpenSettings = {}) }
        compose.onNodeWithText("Off \u00B7 Nothing stopped this week").assertExists()
        compose.onNodeWithContentDescription("Protection off, Nothing stopped this week").assertExists()
    }

    @Test
    fun `the keyword help has four headings and one close button`() {
        var closed = false
        show { KeywordHelpDialog { closed = true } }
        listOf("Words", "Sender names", "Number starts", "Patterns, for advanced users").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun `bank cards are on by default and the setting persists`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = BankCardPrefs.get(context)
        assertTrue(prefs.cardsEnabled)
        prefs.cardsEnabled = false
        assertEquals(false, BankCardPrefs.get(context).cardsEnabled)
    }
}
