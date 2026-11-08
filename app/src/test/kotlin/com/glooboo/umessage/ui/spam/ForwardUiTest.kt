package com.glooboo.umessage.ui.spam

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForwardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun shownNumber(number: String) =
        isolateNumbers(displayNumber(ApplicationProvider.getApplicationContext<android.content.Context>(), number))

    /** The sheet wired to a form held in Compose state, so typing and switches behave as in the app. */
    private fun showSheet(initial: ForwardForm, sims: List<SimInfo> = emptyList(), onSubmit: (ForwardForm) -> Unit = {}) {
        compose.setContent {
            var form by remember { mutableStateOf(initial) }
            val actions = ForwardActions(
                onSenderPattern = { form = form.copy(senderPattern = it) },
                onTarget = { i, v -> form = form.copy(targets = form.targets.toMutableList().also { it[i] = v }) },
                onAddTarget = { form = form.copy(targets = form.targets + "") },
                onIncludeCodes = { on -> form = if (on) form.copy(confirmingCodes = true) else form.copy(includeCodes = false) },
                onConfirmCodes = { form = form.copy(includeCodes = true, confirmingCodes = false) },
                onCancelCodes = { form = form.copy(confirmingCodes = false) },
                onSubmit = { onSubmit(form) },
            )
            UMessageTheme { ForwardSheet(form, sims, actions) }
        }
    }

    private fun saveButton() = compose.onNodeWithTag("forward_save")

    @Test
    fun saveIsEnabledOnlyOnceASenderAndAValidTargetAreFilledIn() {
        var submitted: ForwardForm? = null
        showSheet(ForwardForm()) { submitted = it }
        saveButton().assertIsNotEnabled()

        compose.onNodeWithTag("forward_sender").performTextInput("07121234567")
        compose.onNodeWithTag("forward_target_0").performScrollTo().performTextInput("12")
        compose.onNodeWithText(str(R.string.forward_err_target_invalid)).assertExists()
        saveButton().assertIsNotEnabled()

        compose.onNodeWithTag("forward_target_0").performTextInput("34567")
        saveButton().assertIsEnabled().performClick()
        assertThat(submitted!!.cleanTargets).containsExactly("1234567")
    }

    @Test
    fun aRuleWithoutConditionsSaysWhatIsMissing() {
        showSheet(ForwardForm(senderType = null, targets = listOf("+15550001111")))
        compose.onNodeWithText(str(R.string.forward_sheet_condition_needed)).assertExists()
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun includeCodesShowsTheStrongWarningBeforeTurningOn() {
        showSheet(ForwardForm(senderPattern = "07121234567", targets = listOf("+15550001111")))
        compose.onNodeWithTag("forward_codes").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.forward_codes_message)).assertExists()
        compose.onNode(hasText(str(R.string.forward_codes_confirm)) and hasClickAction()).performClick()
        compose.onNodeWithText(str(R.string.forward_codes_message)).assertDoesNotExist()
    }

    @Test
    fun theSheetSaysEachForwardIsAPaidSms() {
        showSheet(ForwardForm())
        compose.onNodeWithText(str(R.string.forward_sheet_paid)).performScrollTo().assertExists()
    }

    @Test
    fun simPickersAppearOnlyOnDualSimPhones() {
        showSheet(ForwardForm(), sims = listOf(SimInfo(11, 1, "")))
        compose.onNodeWithText(str(R.string.forward_sheet_send_from_label)).assertDoesNotExist()
    }

    @Test
    fun simPickersOnADualSimPhone() {
        showSheet(ForwardForm(), sims = listOf(SimInfo(11, 1, ""), SimInfo(22, 2, "")))
        compose.onNodeWithText(str(R.string.forward_sheet_send_from_label)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.forward_sim_same)).assertExists()
    }

    private fun showForwarding(
        state: ForwardUiState,
        paused: Boolean = false,
        onOpenOptions: () -> Unit = {},
    ) = compose.setContent {
        UMessageTheme {
            ForwardingScreen(
                state,
                ForwardActions(),
                SnackbarHostState(),
                paused = paused,
                onOpenOptions = onOpenOptions,
            )
        }
    }

    @Test
    fun theForwardingScreenListsRulesWithTheirTargetsAndPaidNote() {
        val rule = ForwardRule(
            id = 3, senderType = RuleType.SENDER_ID, senderPattern = "HDFCBK",
            targets = listOf("+15550001111", "+15550002222"), includeCodes = true,
        )
        showForwarding(ForwardUiState(loading = false, rules = listOf(rule)))
        // P2-09: the paid-SMS card is in the empty state and the editor, not above every list.
        compose.onNodeWithText(str(R.string.forward_paid_note)).assertDoesNotExist()
        // Targets are shown the way every number is (grouped, isolated); the stored values stay raw.
        val shown = listOf("+15550001111", "+15550002222").joinToString(", ") { shownNumber(it) }
        compose.onNodeWithText(str(R.string.forward_row_to, shown)).assertExists()
        compose.onNodeWithText(str(R.string.forward_chip_codes)).assertExists()
        compose.onNodeWithText(str(R.string.forward_add), useUnmergedTree = true).assertExists()
        // VS-14 and ID-04: no trash on the card, and a line about its last forward.
        compose.onNodeWithContentDescription(str(R.string.forward_delete_rule)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.fwdfix_card_never)).assertExists()
    }

    /** Round 3: Rules is Allowed and Blocked only; forwarding is neither a tab nor a menu item there. */
    @Test
    fun rulesHasNoForwardingTabOrMenuItem() {
        compose.setContent {
            UMessageTheme { RulesScreen(RulesUiState(loading = false), SnackbarHostState(), RulesActions()) }
        }
        // The old tab and menu labels are gone from the resources, so the test names them as they read.
        compose.onNodeWithText("Forwarding", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.spam_more_options)).performClick()
        compose.onNodeWithText("Forward messages").assertDoesNotExist()
    }

    @Test
    fun theEmptyForwardingScreenShowsThePaidNoteOnceAndNoOptionsYet() {
        showForwarding(ForwardUiState(loading = false))
        compose.onNodeWithText(str(R.string.forward_paid_note)).assertExists()
        compose.onNodeWithText(str(R.string.r3fwd_paused_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.r3fwd_options)).assertDoesNotExist()
    }

    /** U39: the options button appears with the first rule. */
    @Test
    fun theOptionsOpenOnceARuleExists() {
        var opened = false
        val rule = ForwardRule(
            id = 3,
            senderType = RuleType.SENDER_ID,
            senderPattern = "HDFCBK",
            targets = listOf("+15550001111"),
        )
        showForwarding(ForwardUiState(loading = false, rules = listOf(rule)), onOpenOptions = { opened = true })
        compose.onNodeWithContentDescription(str(R.string.r3fwd_options)).performClick()
        assertThat(opened).isTrue()
    }

    /** While "Pause all forwarding" is on, the screen says so and leads to the switch. */
    @Test
    fun pausedForwardingShowsACardThatOpensTheOptions() {
        var opened = false
        showForwarding(ForwardUiState(loading = false), paused = true, onOpenOptions = { opened = true })
        compose.onNodeWithText(str(R.string.r3fwd_paused_title)).assertExists()
        compose.onNodeWithText(str(R.string.r3fwd_paused_action)).performClick()
        assertThat(opened).isTrue()
    }

    /** Intents built for the old Forward tab of Rules still reach forwarding. */
    @Test
    fun oldForwardTabIntentsAreRecognized() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val plain = android.content.Intent(context, RulesActivity::class.java)
        assertThat(RulesActivity.opensForwarding(plain)).isFalse()
        assertThat(RulesActivity.opensForwarding(null)).isFalse()
        val tab = android.content.Intent(plain).putExtra(RulesActivity.EXTRA_OPEN_FORWARD_TAB, true)
        assertThat(RulesActivity.opensForwarding(tab)).isTrue()
        val sender = android.content.Intent(plain).putExtra(RulesActivity.EXTRA_FORWARD_SENDER, "HDFCBK")
        assertThat(RulesActivity.opensForwarding(sender)).isTrue()
        val forwarding = ForwardingActivity.intent(context, " +15550001111 ")
        assertThat(forwarding.getStringExtra(ForwardingActivity.EXTRA_FORWARD_SENDER)).isEqualTo("+15550001111")
        assertThat(ForwardingActivity.intent(context, " ").hasExtra(ForwardingActivity.EXTRA_FORWARD_SENDER)).isFalse()
    }

    @Test
    fun theEditSheetHasDeleteAndSendTest() {
        var deleted = false
        var asked = false
        val rule = ForwardRule(
            id = 3, senderType = RuleType.NUMBER, senderPattern = "1234567", targets = listOf("+15550001111"),
        )
        val actions = ForwardActions(onDeleteFromForm = { deleted = true }, onAskTest = { asked = true })
        compose.setContent { UMessageTheme { ForwardSheet(ForwardForm.of(rule), emptyList(), actions) } }
        compose.onNodeWithTag("forward_send_test").performScrollTo().performClick()
        compose.onNodeWithTag("forward_delete").performScrollTo().performClick()
        assertThat(asked).isTrue()
        assertThat(deleted).isTrue()
    }

    @Test
    fun forwardedTabHintSnackbar_hideRunsTheAction_andIgnoringKeepsTheTab() {
        var hidden = 0
        var done = 0
        val host = SnackbarHostState()
        compose.setContent {
            UMessageTheme {
                androidx.compose.material3.SnackbarHost(host)
                ForwardDialogs(
                    ForwardUiState(forwardedTabAdded = true),
                    ForwardActions(onHideForwardedTab = { hidden++ }, onForwardedTabHintDone = { done++ }),
                    host,
                )
            }
        }
        compose.onNodeWithText(str(R.string.fwdtab_added)).assertExists()
        compose.onNodeWithText(str(R.string.fwdtab_hide)).performClick()
        compose.waitForIdle()
        assertThat(hidden).isEqualTo(1)
        assertThat(done).isEqualTo(0)
    }

    @Test
    fun noLockNoteOffersToSetUpALock() {
        var setUp = false
        compose.setContent {
            UMessageTheme {
                ForwardDialogs(
                    ForwardUiState(loading = false, prompt = ForwardGatePrompt.NO_LOCK_NOTE),
                    ForwardActions(onNoLockSetUp = { setUp = true }),
                )
            }
        }
        compose.onNodeWithText(str(R.string.forward_no_lock_title)).assertExists()
        compose.onNodeWithText(str(R.string.forward_no_lock_set_up)).performClick()
        assertThat(setUp).isTrue()
    }
}
