package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.res.Resources
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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** SU-22 (why a message was blocked) and SU-23 (when it is auto-deleted). */
@RunWith(AndroidJUnit4::class)
class BlockedDetailsTest {

    private val context: Context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val resources: Resources = context.resources
    private val rules = listOf(
        SpamRule(7, ListKind.BLOCK, RuleType.KEYWORD, "free"),
        SpamRule(8, ListKind.BLOCK, RuleType.PREFIX, "+91140"),
        SpamRule(9, ListKind.BLOCK, RuleType.SENDER_ID, "AX-PROMOS"),
        SpamRule(10, ListKind.BLOCK, RuleType.REGEX, "w".repeat(60)),
    ).associateBy { it.id }

    private fun message(reason: DecisionReason, ruleId: Long?) =
        BlockedMessage(1, "+14155550133", "Free", 1, false, 0, reason, ruleId)

    @Test
    fun ruleBlockNamesTheRule() {
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 7), rules)).isEqualTo("Rule: keyword \"free\"")
        // P-2: the prefix is isolated whole, so it keeps its + in front in a right-to-left line.
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 8), rules))
            .isEqualTo("Rule: numbers starting \u2066+91140\u2069")
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 9), rules)).isEqualTo("Rule: sender AX-PROMOS")
    }

    @Test
    fun shortPrefixAndNumberRulesAreIsolated() {
        val more = mapOf(
            11L to SpamRule(11, ListKind.BLOCK, RuleType.PREFIX, "+1"),
            12L to SpamRule(12, ListKind.BLOCK, RuleType.NUMBER, "+15550001234"),
        )
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 11), more)).endsWith("\u2066+1\u2069")
        // The number as the rule row shows it: grouped, in the language's digits, isolated by its digit runs.
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 12), more))
            .endsWith(isolateNumbers(displayNumber(context, "+15550001234")))
    }

    @Test
    fun theAddedAsRuleMessageIsolatesTheValue() {
        // P-5: a number or a prefix (an entry that ended in *), read left to right in any language.
        val text = blockedNumbersEventText(resources, BlockedNumbersEvent.AddedAsRule("+1555"))
        assertThat(text).isEqualTo(resources.getString(R.string.e_bn_added_as_rule, "\u2066+1555\u2069"))
    }

    @Test
    fun longPatternIsCut() {
        val text = ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 10), rules)!!
        assertThat(text).endsWith("…")
        assertThat(text.length).isLessThan(50)
    }

    @Test
    fun deletedRuleSaysSo() {
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, 99), rules)).isEqualTo("Rule since removed")
    }

    @Test
    fun otherReasonsHaveNoRuleText() {
        assertThat(ruleReasonText(context, message(DecisionReason.UNKNOWN_SENDER, null), rules)).isNull()
        assertThat(ruleReasonText(context, message(DecisionReason.BLOCK_RULE, null), rules)).isNull()
    }

    @Test
    fun daysUntilPurgeCountsDown() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertThat(daysUntilPurge(now, now, 30)).isEqualTo(30)
        assertThat(daysUntilPurge(now - 27 * day, now, 30)).isEqualTo(3)
        assertThat(daysUntilPurge(now - 31 * day, now, 30)).isEqualTo(0)
        assertThat(daysUntilPurge(now, now, 0)).isNull()
    }

    @Test
    fun purgeLabel() {
        val day = 24L * 60 * 60 * 1000
        assertThat(purgeLeftText(resources, 0, 29 * day, 30)).isEqualTo("Deleted in 1 day")
        assertThat(purgeLeftText(resources, 0, 31 * day, 30)).isEqualTo("Deleted today")
        assertThat(purgeLeftText(resources, 0, 0, 0)).isNull()
    }
}
