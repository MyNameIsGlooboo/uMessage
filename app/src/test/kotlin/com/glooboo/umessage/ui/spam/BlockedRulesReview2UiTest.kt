package com.glooboo.umessage.ui.spam

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Design review 2 on the Blocked tab, the Rules screen and the Android block list. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedRulesReview2UiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun rulesScreenIsTitledRulesAndShowsAppNotesInWords() {
        // BR-13 and bug 4.
        val state = RulesUiState(
            loading = false,
            tab = ListKind.BLOCK,
            blocked = listOf(
                SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121234567", note = RuleNotes.BLOCKED_BY_YOU),
                SpamRule(2, ListKind.BLOCK, RuleType.KEYWORD, "lottery", note = "gym"),
            ),
        )
        compose.setContent { UMessageTheme { RulesScreen(state, SnackbarHostState(), RulesActions()) } }
        compose.onNodeWithText(str(R.string.e_rules_title)).assertExists()
        compose.onNodeWithText(str(R.string.e_note_blocked_by_you), substring = true).assertExists()
        compose.onNodeWithText(str(R.string.e_note_quoted, "gym"), substring = true).assertExists()
        compose.onNodeWithText(RuleNotes.BLOCKED_BY_YOU).assertDoesNotExist()
    }

    @Test
    fun emptyBlockedTabExampleOpensAConcreteRule() {
        var opened: Triple<ListKind, RuleType, String>? = null
        val actions = RulesActions(onExample = { kind, type, value -> opened = Triple(kind, type, value) })
        val state = RulesUiState(loading = false, tab = ListKind.BLOCK)
        compose.setContent { UMessageTheme { RulesScreen(state, SnackbarHostState(), actions) } }
        compose.onNodeWithText(str(R.string.e_example_block_cancel_word)).performClick()
        assertThat(opened).isEqualTo(Triple(ListKind.BLOCK, RuleType.KEYWORD, RuleExamples.CANCEL_WORD))
    }

    @Test
    fun patternEditorOffersBuildingBlocks() {
        var pattern: String? = null
        val form = RuleForm(kind = ListKind.BLOCK, type = RuleType.REGEX)
        compose.setContent {
            UMessageTheme { AddRuleSheet(form, emptyList(), RulesActions(onPattern = { pattern = it })) }
        }
        compose.onNodeWithText(str(R.string.ux8d_match_label)).assertExists()
        compose.onNodeWithText(str(R.string.e_pattern_link)).performClick()
        assertThat(pattern).isEqualTo(PatternBlock.LINK.template)
    }

    @Test
    fun blockUnknownSwitchShowsOnlyInOffMode() {
        // SE-28 / VS-30.
        val title = str(R.string.blocknum_unknown_title)
        var state by androidx.compose.runtime.mutableStateOf(BlockedNumbersUiState(loading = false))
        compose.setContent {
            UMessageTheme { BlockedNumbersScreen(state, SnackbarHostState(), BlockedNumbersActions()) }
        }
        compose.onNodeWithText(title).assertDoesNotExist()
        state = state.copy(mode = UnknownSenderMode.OFF)
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(str(R.string.e_bn_unknown_body)).assertExists()
    }

    @Test
    fun theShortCodeWarningOffersAUMessageRule() {
        // BR-28 / bug 15. The dialog window itself never settles under Robolectric, so its warning is shown alone.
        var added = false
        compose.setContent { UMessageTheme { ShortCodeWarning(onAddAsRule = { added = true }) } }
        compose.onNodeWithText(str(R.string.e_bn_short_code_warning)).assertExists()
        compose.onNodeWithText(str(R.string.e_bn_add_as_rule)).performClick()
        assertThat(added).isTrue()
    }

    @Test
    fun blockedListShowsTheSenderAndTheRestoredListInTheMenu() {
        // BR-10. The rows read like the Messages tab now (owner notes r3), without day headers.
        val now = System.currentTimeMillis()
        val message = BlockedMessage(1, "+447121234567", "hello", 1, false, now, DecisionReason.UNKNOWN_SENDER)
        val state = BlockedUiState(
            loading = false,
            groups = groupBySender(listOf(message)),
            heldCount = 1,
            stats = SpamStats(blockedTotal = 1, blockedLast7Days = 1, restoredTotal = 2),
            restored = listOf(RestoredEntry("+447120000000", now - 1_000, now)),
        )
        compose.setContent { UMessageTheme { BlockedScreen(state, SnackbarHostState(), BlockedActions()) } }
        compose.onNodeWithText("hello", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.spam_more_options)).performClick()
        compose.onNodeWithText(str(R.string.blocked_menu_restored)).performClick()
        compose.onNodeWithText(str(R.string.e_restored_title)).assertExists()
        compose.onNodeWithText("+447120000000").assertExists()
    }
}
