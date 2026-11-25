package com.glooboo.umessage.ui.spam

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** U02 / U19 / U45: the one Spam protection page, with action names and one Recent activity link. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class SpamProtectionPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(id)

    private fun show(actions: SettingsActions, showOldBlockedWords: Boolean = false) = compose.setContent {
        UMessageTheme {
            SettingsScreen(
                SettingsUiState(loading = false),
                actions,
                showOldBlockedWords = showOldBlockedWords,
            )
        }
    }

    private fun showSettings(settings: SpamSettings) = compose.setContent {
        UMessageTheme { SettingsScreen(SettingsUiState(loading = false, settings = settings), SettingsActions()) }
    }

    private fun grantContacts() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun theOnePageHasTheSwitchTheRulesAndNamedSwitches() {
        show(SettingsActions())
        compose.onNodeWithText(str(R.string.settings_nav_spam_page_title)).assertExists()
        compose.onNodeWithText(str(R.string.pt_filter_on_summary)).assertExists()
        compose.onNodeWithText(str(R.string.settings_block_umessage_title)).assertExists()
        compose.onNodeWithText(str(R.string.i07_lock_title)).assertExists()
        compose.onNodeWithText(str(R.string.notif_open_link_title)).assertExists()
    }

    @Test
    fun rulesAndRecentActivityAreLinksOnThePage() {
        var rules = false
        var log = false
        show(SettingsActions(onOpenRules = { rules = true }, onOpenDecisionLog = { log = true }))
        compose.onNodeWithText(str(R.string.settings_block_umessage_title)).performClick()
        compose.onNodeWithText(str(R.string.settings_nav_decision_log)).performClick()
        assertThat(rules).isTrue()
        assertThat(log).isTrue()
    }

    @Test
    fun noRecentActivityRowWithoutTheLog() {
        show(SettingsActions())
        compose.onNodeWithText(str(R.string.settings_nav_decision_log)).assertDoesNotExist()
    }

    // B3: the blocked numbers list and the old blocked words moved here from the short copy inside Settings.
    @Test
    fun blockedNumbersAndOldWordsRowsLeadToTheirScreens() {
        var numbers = false
        var words = false
        show(
            SettingsActions(
                onOpenBlockedNumbers = { numbers = true },
                onOpenBlockedKeywords = { words = true },
            ),
            showOldBlockedWords = true,
        )
        compose.onNodeWithText(str(R.string.rules2_bn_title)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_old_blocked_words)).performScrollTo().performClick()
        assertThat(numbers).isTrue()
        assertThat(words).isTrue()
    }

    @Test
    fun theOldBlockedWordsRowHidesWhenTheListIsEmpty() {
        show(SettingsActions(), showOldBlockedWords = false)
        compose.onNodeWithText(str(R.string.settings_old_blocked_words)).assertDoesNotExist()
    }

    // The "i" beside the filter header opens the text with the sorting card the switch draws.
    @Test
    fun theInfoIconOpensTheSortingCardWithContacts() {
        grantContacts()
        showSettings(SpamSettings())
        compose.onNodeWithTag("info_icon").performClick()
        compose.onNodeWithText("Unknown sender", useUnmergedTree = true).assertExists()
    }

    // Without Contacts the filter lets unknown numbers through, so the card would be false: it is not shown.
    @Test
    fun theInfoDialogHasNoCardWithoutContactsWhileFilterOn() {
        showSettings(SpamSettings())
        compose.onNodeWithTag("info_icon").performClick()
        compose.onNodeWithText("Unknown sender", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theInfoDialogKeepsTheCardWithTheFilterOffWithoutContacts() {
        showSettings(SpamSettings(filterOn = false))
        compose.onNodeWithTag("info_icon").performClick()
        compose.onNodeWithText("Unknown sender", useUnmergedTree = true).assertExists()
    }
}
