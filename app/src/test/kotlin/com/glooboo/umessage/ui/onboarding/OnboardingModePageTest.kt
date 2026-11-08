package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The unknown-senders step: three short radio rows, the purge row always visible unless Off, delivery reports. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingModePageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(mode: UnknownSenderMode, onMode: (UnknownSenderMode) -> Unit = {}) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            ModePage(
                modifier = Modifier.fillMaxSize(),
                mode = mode,
                onMode = onMode,
                purgeDays = 30,
                onPurgeDays = {},
            )
        }
    }

    @Test
    fun `Smart is the selected row and every row has a name and a short description`() {
        page(UnknownSenderMode.SMART)
        compose.onNode(isSelectable() and hasText("Smart")).assertIsSelected()
        compose.onNode(isSelectable() and hasText("Strict")).assertIsNotSelected()
        compose.onNode(isSelectable() and hasText("Off")).assertIsNotSelected()
        compose.onNodeWithText("Hold all in Spam").assertIsDisplayed()
        compose.onNodeWithText("Hold, but allow code messages").assertIsDisplayed()
        compose.onNodeWithText("Do not filter").assertIsDisplayed()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
    }

    @Test
    fun `the keep-blocked line and the delivery reports switch stay visible, the long intro is gone`() {
        page(UnknownSenderMode.SMART)
        compose.onNodeWithText("Keep spam for").assertIsDisplayed()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        compose.onNodeWithText("Get delivery reports").assertIsDisplayed()
        compose.onNodeWithText("An unknown sender is a number", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the purge line is hidden when filtering is Off`() {
        page(UnknownSenderMode.OFF)
        compose.onNodeWithText("Keep spam for").assertDoesNotExist()
    }

    @Test
    fun `tapping a row reports its mode`() {
        var picked: UnknownSenderMode? = null
        page(UnknownSenderMode.SMART, onMode = { picked = it })
        compose.onNodeWithText("Strict").performClick()
        assertEquals(UnknownSenderMode.STRICT, picked)
    }
}
