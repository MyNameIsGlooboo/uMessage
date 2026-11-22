package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.detailsRuleText
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.log.LogContext
import com.glooboo.umessage.ui.log.LogRowGroup
import com.glooboo.umessage.ui.log.rememberRowText
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * T-3 (step J6, Fable review 2): each place that shows a rule's value calls the shared helper. A prefix must come out
 * isolated whole ("\u2066+1\u2069"); a bare "+1" would show as "1+" in right-to-left text. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class RuleValueCallSitesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val isolated = "\u2066+1\u2069"
    private val prefix = SpamRule(1, ListKind.BLOCK, RuleType.PREFIX, "+1")

    private fun entry(decision: String, reason: String, ruleId: Long?, label: String = "") = SpamLogEntry(
        1L, SENDER, 0, decision, reason, "SMS", ruleId = ruleId, ruleLabel = label,
    )

    private val blockedByPrefix = entry("BLOCK", DecisionReason.BLOCK_RULE.name, 1L, "+1")

    @Test
    fun theDetailsRuleLineIsolatesAPrefix() {
        // F-1
        assertThat(detailsRuleText(context, blockedByPrefix, SENDER, listOf(prefix))).isEqualTo(isolated)
        // Once the rule is deleted the stored label looks the same (F-6).
        assertThat(detailsRuleText(context, blockedByPrefix, SENDER, emptyList())).isEqualTo(isolated)
    }

    @Test
    fun anAllowDecisionNamesTheRuleThatAllowedIt() {
        // G-2: the keyword rule allowed the message; a number allow rule added later must not take its place.
        val rules = listOf(
            SpamRule(2, ListKind.ALLOW, RuleType.KEYWORD, "win 5000"),
            SpamRule(3, ListKind.ALLOW, RuleType.NUMBER, SENDER),
        )
        val allowed = entry("ALLOW", DecisionReason.ALLOW_RULE.name, 2L)
        assertThat(detailsRuleText(context, allowed, SENDER, rules)).isEqualTo("win 5000")
    }

    @Test
    fun theTestSheetIsolatesAPrefix() {
        // F-2
        assertThat(testRuleText(context, 1L, listOf(prefix))).isEqualTo(isolated)
        assertThat(testRuleText(context, null, listOf(prefix))).isNull()
    }

    @Test
    fun theMovedMessageAndTheWildcardRowIsolateTheValue() {
        // F-3, both places.
        assertThat(ruleValueMessage(context.resources, R.string.e_bn_moved, "+1"))
            .isEqualTo(context.getString(R.string.e_bn_moved, isolated))
        val wildcard = BlockedNumberItem(1L, "+1555*")
        assertThat(blockedEntryText(wildcard, Locale.ENGLISH, "+1555*")).isEqualTo("\u2066+1555*\u2069")
    }

    @Test
    fun theValueFieldRunsLeftToRightForANumberOrAPrefix() {
        // F-5, the field.
        assertThat(ruleValueStyle(TextStyle(), RuleType.PREFIX).textDirection).isEqualTo(TextDirection.Ltr)
        assertThat(ruleValueStyle(TextStyle(), RuleType.NUMBER).textDirection).isEqualTo(TextDirection.Ltr)
        assertThat(ruleValueStyle(TextStyle(), RuleType.KEYWORD).textDirection).isEqualTo(TextDirection.Unspecified)
        assertThat(ruleValueStyle(TextStyle(), null).textDirection).isEqualTo(TextDirection.Unspecified)
    }

    @Test
    fun theBlockedChatsReasonBadgeOpensTheBlockList() {
        val intent = BlockedThreadActivity.rulesIntent(context)
        assertThat(intent.component?.className).isEqualTo(RulesActivity::class.java.name)
        assertThat(intent.getStringExtra(RulesActivity.EXTRA_TAB)).isEqualTo(ListKind.BLOCK.name)
    }

    @Test
    fun theHintAndTheLogRowIsolateAPrefix() {
        // F-5, the hint; and the log's call in rememberRowText, live and deleted.
        var shown = emptyList<String?>()
        compose.setContent {
            UMessageTheme {
                shown = listOf(
                    typeHint(RuleType.PREFIX),
                    rememberRowText(LogRowGroup(listOf(blockedByPrefix)), LogContext(rules = listOf(prefix))).reason,
                    rememberRowText(LogRowGroup(listOf(blockedByPrefix)), LogContext()).reason,
                )
            }
        }
        compose.waitForIdle()
        val hint = context.getString(R.string.rules2_type_prefix_hint)
        assertThat(shown[0]).isEqualTo("\u2066${hint}\u2069")
        val blockedBy = context.getString(R.string.w2c_log_blocked_by_rule, isolated)
        assertThat(shown[1]).isEqualTo(blockedBy)
        assertThat(shown[2]).isEqualTo(blockedBy)
    }

    private companion object {
        const val SENDER = "+15550001234"
    }
}
