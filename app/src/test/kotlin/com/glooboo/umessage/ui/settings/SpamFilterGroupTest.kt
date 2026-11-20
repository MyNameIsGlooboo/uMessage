package com.glooboo.umessage.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.spam.SortChange
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Provider truth (4.6, U11): the filter switch on the main Settings page asks first, and a rotation keeps the ask. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class SpamFilterGroupTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources
    private val repo = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val switchModel = SpamFilterSwitchViewModel(app, { repo }, moveCounter = { { 6 } })
    private val spam = SpamSummary(UnknownSenderMode.SMART, 0, 0, 0, filterOn = true)

    private fun stored(): SpamSettings = runBlocking { repo.settings.first() }

    @Test
    fun theSwitchAsksWithTheCountAndOnlyTheConfirmTurnsItOff() {
        compose.setContent { UMessageTheme { SpamFilterGroup(spam, switchModel) } }
        compose.onNodeWithText(res.getString(R.string.pt_filter_on_summary)).performClick()
        compose.onNodeWithText(res.getString(R.string.pt_filter_off_confirm_title)).assertExists()
        compose.onNodeWithText(res.getQuantityString(R.plurals.pt_filter_off_confirm_body, 6, 6)).assertExists()
        assertThat(stored().filterOn).isTrue()

        compose.onNodeWithText(res.getString(R.string.pt_turn_off)).performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(stored().filterOn).isFalse()
        compose.onNodeWithText(res.getString(R.string.pt_filter_off_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun aRotationKeepsTheOpenConfirm() {
        val restore = StateRestorationTester(compose)
        restore.setContent { UMessageTheme { SpamFilterGroup(spam, switchModel) } }
        compose.onNodeWithText(res.getString(R.string.pt_filter_on_summary)).performClick()
        compose.onNodeWithText(res.getString(R.string.pt_filter_off_confirm_title)).assertExists()

        restore.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(res.getString(R.string.pt_filter_off_confirm_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.spam_cancel)).performClick()
        compose.onNodeWithText(res.getString(R.string.pt_filter_off_confirm_title)).assertDoesNotExist()
        assertThat(stored().filterOn).isTrue()
    }

    @Test
    fun aFailedOrImpossibleWriteSaysSoAndAGoodOneDoesNot() {
        val failing = SpamFilterSwitchViewModel(app, { error("store gone") }, moveCounter = { { 6 } })
        val missing = SpamFilterSwitchViewModel(app, { null }, moveCounter = { { 6 } })
        assertThat(failedAfterConfirm(failing)).isTrue()
        assertThat(failedAfterConfirm(missing)).isTrue()
        assertThat(failedAfterConfirm(switchModel)).isFalse()
        assertThat(stored().filterOn).isFalse()
    }

    private fun failedAfterConfirm(model: SpamFilterSwitchViewModel): Boolean {
        model.switches.request(SortChange.Filter(on = false))
        shadowOf(Looper.getMainLooper()).idle()
        model.switches.confirm()
        shadowOf(Looper.getMainLooper()).idle()
        return runBlocking { withTimeoutOrNull(FAILURE_WAIT_MS) { model.failureFlow.first() } } != null
    }

    private companion object {
        const val FAILURE_WAIT_MS = 500L
    }
}
