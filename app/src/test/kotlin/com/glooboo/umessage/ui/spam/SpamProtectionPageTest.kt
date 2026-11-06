package com.glooboo.umessage.ui.spam

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** U02 / U19 / U45: the one Spam protection page, with action names and one Recent activity link. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class SpamProtectionPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(id)

    private fun show(actions: SettingsActions) = compose.setContent {
        UMessageTheme { SettingsScreen(SettingsUiState(loading = false), actions) }
    }

    @Test
    fun theOnePageHasTheModeTheRulesAndNamedSwitches() {
        show(SettingsActions())
        compose.onNodeWithText(str(R.string.settings_nav_spam_page_title)).assertExists()
        compose.onNodeWithText(str(R.string.spam_mode_smart)).assertExists()
        compose.onNodeWithText(str(R.string.settings_spam_open_rules)).assertExists()
        compose.onNodeWithText(str(R.string.i07_lock_title)).assertExists()
        compose.onNodeWithText(str(R.string.notif_open_link_title)).assertExists()
    }

    @Test
    fun rulesAndRecentActivityAreLinksOnThePage() {
        var rules = false
        var log = false
        show(SettingsActions(onOpenRules = { rules = true }, onOpenDecisionLog = { log = true }))
        compose.onNodeWithText(str(R.string.settings_spam_open_rules)).performClick()
        compose.onNodeWithText(str(R.string.settings_nav_decision_log)).performClick()
        assertThat(rules).isTrue()
        assertThat(log).isTrue()
    }

    @Test
    fun noRecentActivityRowWithoutTheLog() {
        show(SettingsActions())
        compose.onNodeWithText(str(R.string.settings_nav_decision_log)).assertDoesNotExist()
    }
}
