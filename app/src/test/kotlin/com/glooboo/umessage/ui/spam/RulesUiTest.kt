package com.glooboo.umessage.ui.spam

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import org.robolectric.annotation.Config
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.keywords.BlockedKeywordsScreen
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulesUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun shownNumber(number: String) =
        isolateNumbers(displayNumber(ApplicationProvider.getApplicationContext<android.content.Context>(), number))

    private fun form(kind: ListKind, type: RuleType, pattern: String, existing: List<SpamRule> = emptyList()): RuleForm {
        val validation = RuleValidator.validate(kind, type, pattern)
        val warnings = (validation as? RuleValidation.Valid)?.let { RuleValidator.warnings(kind, type, it.pattern, existing, null) }.orEmpty()
        return RuleForm(kind = kind, type = type, pattern = pattern, validation = validation, warnings = warnings)
    }

    private fun showSheet(form: RuleForm) = compose.setContent {
        UMessageTheme { AddRuleSheet(form, emptyList(), RulesActions()) }
    }

    @Test
    // The form scrolls above a sticky Cancel / Add bar and the sheet is capped at 92% of the screen height. On
    // Robolectric's default 320x470dp screen the error under the field is scrolled out of view, so use a tall one.
    @Config(qualifiers = "w411dp-h2000dp")
    fun unsafeRegexShowsAnInlineErrorAndCannotBeAdded() {
        showSheet(form(ListKind.BLOCK, RuleType.REGEX, "(a+)+"))
        compose.onNodeWithText(str(R.string.spam_err_regex_unsafe)).assertIsDisplayed()
        compose.onNode(hasText(str(R.string.spam_sheet_add)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun allowKeywordWarnsThatItSkipsEveryBlock() {
        showSheet(form(ListKind.ALLOW, RuleType.KEYWORD, "bank"))
        compose.onNodeWithText(str(R.string.spam_warn_allow_skips_blocks)).assertExists()
        compose.onNodeWithText(str(R.string.spam_warn_common_word)).assertExists()
    }

    @Test
    fun failedSaveSaysSo() {
        showSheet(form(ListKind.BLOCK, RuleType.PREFIX, "+44712").copy(saveFailed = true))
        compose.onNodeWithText(str(R.string.spam_sheet_save_failed)).assertExists()
    }

    @Test
    fun blockKeywordHelpSaysItMatchesInsideWords() {
        showSheet(RuleForm(kind = ListKind.BLOCK, type = RuleType.KEYWORD))
        compose.onNodeWithText(str(R.string.spam_type_keyword_help)).assertExists()
    }

    @Test
    fun editSheetSaysEditAndSave() {
        val rule = SpamRule(3, ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        showSheet(form(ListKind.BLOCK, RuleType.KEYWORD, "lottery").copy(editing = rule))
        compose.onNodeWithText(str(R.string.rules_sheet_title_edit_block)).assertExists()
        compose.onNode(hasText(str(R.string.rules_sheet_save)) and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun addSheetAsksWhatToMatchAndKeepsPrefixAndPatternBehindMore() {
        showSheet(RuleForm(kind = ListKind.ALLOW, type = RuleType.NUMBER))
        // The title and the choice chip carry the same words.
        compose.onAllNodesWithText(str(R.string.ux8d_sheet_title_allow)).assertCountEquals(2)
        compose.onNodeWithText(str(R.string.ux8d_match_word)).assertExists()
        compose.onNodeWithText(str(R.string.ux8d_match_prefix)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ux8d_match_more)).performClick()
        compose.onNodeWithText(str(R.string.ux8d_match_prefix)).assertExists()
        compose.onNodeWithText(str(R.string.ux8d_match_pattern)).assertExists()
    }

    @Test
    // The sample field opens below the rule field: use a tall screen so it is inside the visible sheet.
    @Config(qualifiers = "w411dp-h2000dp")
    fun sampleTestIsATextButtonUntilTapped() {
        showSheet(form(ListKind.BLOCK, RuleType.KEYWORD, "lottery"))
        val sampleField = hasSetTextAction() and hasText(str(R.string.rules2_test_label))
        compose.onAllNodes(sampleField).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.rules2_test_label)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(sampleField).assertCountEquals(1)
    }

    @Test
    fun aShortListHasNoSearchAndRowsNameTheirType() {
        showRules(RulesUiState(loading = false, tab = ListKind.BLOCK, blocked = sampleRules))
        compose.onNodeWithText(str(R.string.rules_search_hint)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ux8d_row_type_word)).assertExists()
        compose.onNodeWithText(str(R.string.ux8d_row_type_number)).assertExists()
    }

    @Test
    fun aLongListShowsSearch() {
        val many = (1L..6L).map { SpamRule(it, ListKind.BLOCK, RuleType.KEYWORD, "word$it") }
        showRules(RulesUiState(loading = false, tab = ListKind.BLOCK, blocked = many))
        compose.onNodeWithText(str(R.string.rules_search_hint)).assertExists()
    }

    private val sampleRules = listOf(
        SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "lottery"),
        SpamRule(2, ListKind.BLOCK, RuleType.NUMBER, "+447121234567"),
    )

    private fun showRules(state: RulesUiState, actions: RulesActions = RulesActions()) = compose.setContent {
        UMessageTheme { RulesScreen(state, SnackbarHostState(), actions) }
    }

    @Test
    fun tappingARuleEditsItAndLongPressStartsPicking() {
        var edited: SpamRule? = null
        var toggled: Long? = null
        showRules(
            RulesUiState(loading = false, tab = ListKind.BLOCK, blocked = sampleRules),
            RulesActions(onEdit = { edited = it }, onToggleSelected = { toggled = it }),
        )
        compose.onNodeWithText("lottery").performClick()
        assertThat(edited?.id).isEqualTo(1L)
        compose.onNodeWithText(shownNumber("+447121234567")).performTouchInput { longClick() }
        assertThat(toggled).isEqualTo(2L)
    }

    @Test
    fun pickingShowsTheCountAndBulkActions() {
        showRules(RulesUiState(loading = false, tab = ListKind.BLOCK, blocked = sampleRules, selected = setOf(1L)))
        val count = ApplicationProvider.getApplicationContext<android.content.Context>().resources
            .getQuantityString(R.plurals.screens_selected_count, 1, 1)
        compose.onNodeWithText(count).assertExists()
        compose.onNodeWithContentDescription(str(R.string.rules_delete_selected)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.rules_export_selected)).assertDoesNotExist()
    }

    @Test
    fun searchWithNoMatchSaysSo() {
        showRules(RulesUiState(loading = false, tab = ListKind.BLOCK, blocked = sampleRules, query = "zzz"))
        compose.onNodeWithText(str(R.string.rules_search_empty_title)).assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp")
    fun oldListsShowReadOnlyOnTheBlockedTabWithTheBanner() {
        val legacy = LegacyLists(keywords = listOf("free gift"), numbers = listOf(BlockedNumberItem(5, "+447120000000")))
        showRules(RulesUiState(loading = false, tab = ListKind.BLOCK, legacy = legacy))
        compose.onNodeWithText(str(R.string.rules2_legacy_banner_title)).assertExists()
        compose.onNodeWithText("free gift").assertExists()
        compose.onNodeWithText(str(R.string.rules_legacy_section_keywords)).assertExists()
        // Shown like every other number (displayNumber), so grouped and isolated.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shown = com.glooboo.umessage.ui.components.isolateNumbers(
            com.glooboo.umessage.ui.components.displayNumber(context, "+447120000000"),
        )
        compose.onNodeWithText(shown).assertExists()
        compose.onNodeWithText(str(R.string.rules2_system_header)).assertExists()
    }

    @Test
    fun migrationDialogOffersRemovalOnlyOnceNumbersAreMoved() {
        val lists = LegacyLists(keywords = listOf("lottery"), numbers = listOf(BlockedNumberItem(5, "+447120000000")))
        val plan = LegacyMigration.plan(lists, emptyList())
        compose.setContent { UMessageTheme { MigrationDialogContent(MigrationDialog(plan), RulesActions()) } }
        compose.onNodeWithText(str(R.string.rules_migrate_remove)).assertIsNotEnabled()
        // SE-34: the calls warning sits under the box it belongs to, as its supporting line.
        compose.onNodeWithText(str(R.string.e_migrate_calls_note), substring = true).assertExists()
        // The points are drawn as bullet rows: the bullet is its own text, so the line is the string without it.
        compose.onNodeWithText(str(R.string.e_migrate_point_blocked).trimStart('•', ' ', ' ')).assertExists()
    }

    @Test
    fun ruleUndoStaysForTheLongUndoWindow() {
        // A Short snackbar (about 4 s) was too quick to tap Undo after deleting a rule.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val host = androidx.compose.runtime.remember { SnackbarHostState() }
            androidx.compose.runtime.LaunchedEffect(Unit) { host.showUndo("Rule deleted", "Undo") }
            UMessageTheme { androidx.compose.material3.SnackbarHost(host) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Undo").assertExists()
        compose.mainClock.advanceTimeBy(RULES_UNDO_MS - 2_000)
        compose.onNodeWithText("Undo").assertExists()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun spamSettingsHaveNoSeparatePasscodeSwitch() {
        compose.setContent { UMessageTheme { SettingsScreen(SettingsUiState(loading = false, settings = SpamSettings()), SettingsActions()) } }
        compose.onNodeWithText(str(R.string.spam_mode_smart_body)).assertExists()
        compose.onNodeWithText("passcode", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun keywordDialogKeepsTypedTextAcrossRecreation() {
        // SU-38: rotating used to close the dialog and lose the text.
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UMessageTheme {
                BlockedKeywordsScreen(
                    keywords = listOf("lottery"),
                    loaded = true,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {}, onSave = { _, _ -> }, onCopy = {}, onDelete = {}, onExport = {}, onImport = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.screens_keywords_add), useUnmergedTree = true).performClick()
        compose.onNode(hasText(str(R.string.keyword))).performTextInput("free gift")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("free gift").assertExists()
    }
}
