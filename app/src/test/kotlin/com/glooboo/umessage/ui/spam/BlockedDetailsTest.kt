package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** SU-22 (why a message was blocked) and SU-23 (when it is auto-deleted). */
@RunWith(AndroidJUnit4::class)
class BlockedDetailsTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources
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
        assertThat(ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, 7), rules)).isEqualTo("Rule: keyword \"free\"")
        assertThat(ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, 8), rules)).isEqualTo("Rule: numbers starting +91140")
        assertThat(ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, 9), rules)).isEqualTo("Rule: sender AX-PROMOS")
    }

    @Test
    fun longPatternIsCut() {
        val text = ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, 10), rules)!!
        assertThat(text).endsWith("…")
        assertThat(text.length).isLessThan(50)
    }

    @Test
    fun deletedRuleSaysSo() {
        assertThat(ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, 99), rules)).isEqualTo("Rule since removed")
    }

    @Test
    fun otherReasonsHaveNoRuleText() {
        assertThat(ruleReasonText(resources, message(DecisionReason.UNKNOWN_SENDER, null), rules)).isNull()
        assertThat(ruleReasonText(resources, message(DecisionReason.BLOCK_RULE, null), rules)).isNull()
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
