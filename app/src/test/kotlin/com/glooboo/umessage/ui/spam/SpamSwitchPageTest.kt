package com.glooboo.umessage.ui.spam

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.backup.BackupSettings
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.settings.RestoreSummary
import com.glooboo.umessage.ui.settings.search.SettingsIndex
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Provider truth (4.6): the Spam protection page has one filter switch that asks first, no mode names, no "numbers
 * from other countries", and an auto-delete warning; the settings search and the restore summary follow.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class SpamSwitchPageTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(id)

    private fun show(state: SettingsUiState, actions: SettingsActions = SettingsActions()) = compose.setContent {
        UMessageTheme { SettingsScreen(state, actions) }
    }

    @Test
    fun theSwitchReplacesTheModesAndTheForeignNumbersOption() {
        show(SettingsUiState(loading = false, settings = SpamSettings(filterOn = true)))
        compose.onNodeWithText(str(R.string.pt_filter_on_summary)).assertExists()
        // G-16: "Spam filter" is read once, as the heading; the switch says what it does.
        compose.onAllNodesWithText(str(R.string.spam_settings_unknown_title), useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithText(str(R.string.spam_mode_strict)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.spam_mode_smart)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.region_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.settings_nav_purge_row)).assertExists()
    }

    @Test
    fun tappingTheSwitchAsksInsteadOfChangingIt() {
        var asked: Boolean? = null
        show(
            SettingsUiState(loading = false, settings = SpamSettings(filterOn = true)),
            SettingsActions(onFilter = { asked = it }),
        )
        compose.onNodeWithText(str(R.string.pt_filter_on_summary)).performClick()
        assertThat(asked).isFalse()
    }

    @Test
    fun theOffSwitchSaysWhatOffDoes() {
        show(SettingsUiState(loading = false, settings = SpamSettings(filterOn = false)))
        compose.onNodeWithText(str(R.string.pt_filter_off_summary)).assertExists()
    }

    @Test
    fun aPendingConfirmShowsAndItsButtonsAnswer() {
        var confirmed = 0
        var dismissed = 0
        show(
            SettingsUiState(
                loading = false,
                settings = SpamSettings(filterOn = true),
                switchConfirm = SwitchConfirm(SortChange.Filter(false), null),
            ),
            SettingsActions(onConfirmSwitch = { confirmed++ }, onDismissSwitch = { dismissed++ }),
        )
        compose.onNodeWithText(str(R.string.pt_filter_off_confirm_title)).assertExists()
        compose.onNodeWithText(str(R.string.pt_turn_off)).performClick()
        compose.onNodeWithText(str(R.string.spam_cancel)).performClick()
        assertThat(confirmed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun aPendingAutoDeleteShowsTheWarning() {
        var confirmed = 0
        show(
            SettingsUiState(loading = false, purgeWarning = 30),
            SettingsActions(onConfirmPurge = { confirmed++ }),
        )
        compose.onNodeWithText(str(R.string.pt_purge_warn_title)).assertExists()
        compose.onNodeWithText(str(R.string.cleanup_bin_confirm)).performClick()
        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun theSearchFindsTheSwitchButNotTheOldModes() {
        val titles = SettingsIndex.entries.map { it.titleRes }
        assertThat(titles).contains(R.string.spam_settings_unknown_title)
        assertThat(titles).containsNoneOf(
            R.string.spam_mode_strict, R.string.spam_mode_smart, R.string.spam_mode_off, R.string.region_title,
        )
        ProviderTruthFlag.enabled = false
        assertThat(SettingsIndex.entries.map { it.titleRes }).contains(R.string.spam_mode_smart)
    }

    @Test
    fun theRestoreSummaryNamesTheNewSwitches() {
        // G-17: the switch and the old mode are one setting, listed once.
        assertThat(RestoreSummary.labelOf(BackupSettings.FILTER_ON))
            .isEqualTo(RestoreSummary.labelOf(BackupSettings.SPAM_MODE))
        assertThat(RestoreSummary.labelOf(BackupSettings.CODE_GROUP_ON)).isEqualTo(R.string.pt_group_code_title)
        assertThat(RestoreSummary.labelOf(BackupSettings.WROTE_GROUP_ON)).isEqualTo(R.string.pt_group_wrote_title)
    }
}
