package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The emulator recheck of design review 2 on the Blocked tab, the Rules screen and the Android block list. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedRulesRecheckUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun shown(number: String) = isolateNumbers(displayNumber(context, number))

    @Test
    fun rulesMenuOpensTheBackupPageInsteadOfItsOwnImportAndExport() {
        // SE-16: one place for backups.
        var opened = false
        val state = RulesUiState(loading = false, tab = ListKind.BLOCK)
        compose.setContent {
            UMessageTheme { RulesScreen(state, SnackbarHostState(), RulesActions(onOpenBackup = { opened = true })) }
        }
        compose.onNodeWithContentDescription(str(R.string.spam_more_options)).performClick()
        compose.onNodeWithText(str(R.string.rules2_menu_import)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.rules2_menu_export)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.e_rules_menu_backup)).performClick()
        assertThat(opened).isTrue()
    }

    @Test
    fun androidBlockListRowsNameTheirTypeOnce() {
        // BR-25: the icon says "Number"; no chip repeats it.
        val number = "+447120000000"
        val state = RulesUiState(
            loading = false,
            tab = ListKind.BLOCK,
            legacy = LegacyLists(numbers = listOf(BlockedNumberItem(5, number)), offerDismissed = true),
        )
        compose.setContent { UMessageTheme { RulesScreen(state, SnackbarHostState(), RulesActions()) } }
        val row = hasText(shown(number)) and hasText(str(R.string.rules_legacy_chip_system))
        compose.onNode(row).assertExists()
        compose.onNode(row and hasText(str(R.string.spam_type_number))).assertDoesNotExist()
    }

    @Test
    fun androidListRowsUnblockWithAVisibleButtonAndShowNumbersAsEverywhereElse() {
        // SE-30, and a 7-digit number is not a short code (recheck bug 2).
        var unblocked: Set<Long>? = null
        val state = BlockedNumbersUiState(loading = false, numbers = listOf(BlockedNumberItem(1, "5559990")))
        compose.setContent {
            UMessageTheme {
                BlockedNumbersScreen(state, SnackbarHostState(), BlockedNumbersActions(onUnblock = { unblocked = it }))
            }
        }
        compose.onNodeWithText(shown("5559990")).assertExists()
        compose.onNodeWithText(str(R.string.e_bn_row_short_code)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.blocknum_unblock_for, shown("5559990"))).performClick()
        assertThat(unblocked).containsExactly(1L)
    }

    @Test
    fun androidListRowMenuOffersUnblock() {
        // Owner notes r3: Unblock is in the row's menu as well, next to Copy and the move into rules.
        var unblocked: Set<Long>? = null
        val state = BlockedNumbersUiState(loading = false, numbers = listOf(BlockedNumberItem(4, "5559990")))
        compose.setContent {
            UMessageTheme {
                BlockedNumbersScreen(state, SnackbarHostState(), BlockedNumbersActions(onUnblock = { unblocked = it }))
            }
        }
        compose.onNodeWithContentDescription(str(R.string.blocknum_more_for, shown("5559990"))).performClick()
        compose.onNodeWithText(str(R.string.blocknum_copy)).assertExists()
        compose.onNodeWithText(str(R.string.e_bn_move_one)).assertExists()
        compose.onNodeWithText(str(R.string.blocknum_unblock)).performClick()
        assertThat(unblocked).containsExactly(4L)
    }

    @Test
    fun aSenderRowSaysWhyItsLatestMessageWasHeldAndOpensTheThread() {
        // Owner notes r3: one row per sender like the Messages tab; the reason is only spoken, tap opens the thread.
        val now = System.currentTimeMillis()
        val address = "5559876"
        val messages = listOf(
            BlockedMessage(1, address, "win a lottery now", 1, false, now, DecisionReason.BLOCK_RULE, ruleId = 7),
            BlockedMessage(2, address, "hello there", 1, false, now - 60_000, DecisionReason.UNKNOWN_SENDER),
        )
        val state = BlockedUiState(
            loading = false,
            groups = groupBySender(messages),
            rules = mapOf(7L to SpamRule(7, ListKind.BLOCK, RuleType.KEYWORD, "lottery")),
        )
        var opened: String? = null
        compose.setContent {
            UMessageTheme { BlockedScreen(state, SnackbarHostState(), BlockedActions(onOpenSender = { opened = it })) }
        }
        compose.onNode(hasContentDescription(str(R.string.spam_reason_rule_keyword, "lottery"), substring = true))
            .assertExists()
        // The older message is not shown inline any more; it is in the thread.
        compose.onNodeWithText("hello there", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(shown(address), useUnmergedTree = true).performClick()
        assertThat(opened).isEqualTo(address)
    }

    @Test
    fun theAllowedSnackbarMentionsBothSimsOnlyOnADualSimPhone() {
        // Recheck bug 5.
        val resources = context.resources
        assertThat(allowedSnackText(resources, listOf("555"), dualSim = true))
            .isEqualTo(str(R.string.e_snack_allowed_one, "555"))
        assertThat(allowedSnackText(resources, listOf("555"), dualSim = false))
            .isEqualTo(str(R.string.e_snack_allowed_one_single_sim, "555"))
        assertThat(allowedSnackText(resources, listOf("1", "2"), dualSim = false))
            .isEqualTo(resources.getQuantityString(R.plurals.e_snack_allowed_many_single_sim, 2, 2))
        assertThat(allowedSnackText(resources, listOf("1", "2"), dualSim = true))
            .isEqualTo(resources.getQuantityString(R.plurals.e_snack_allowed_many, 2, 2))
    }
}
