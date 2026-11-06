package com.glooboo.umessage.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecisionRuleTextTest {

    private fun block(ruleId: Long?, label: String = "", reason: String = "BLOCK_RULE") =
        SpamLogEntry(1, "+15551234567", 1, "BLOCK", reason, "SMS", ruleId, label)

    private val rule = SpamRule(id = 5, kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery")

    @Test
    fun entryWithRuleRoundTripsThroughTheFile() {
        val back = SpamLog.decode(SpamLog.encode(block(5, "lot\ttery")))!!
        assertThat(back.ruleId).isEqualTo(5L)
        assertThat(back.ruleLabel).isEqualTo("lot tery")
    }

    @Test
    fun oldSixColumnLineStillDecodes() {
        val back = SpamLog.decode("1\t+1555\t1\tBLOCK\tBLOCK_RULE\tSMS")!!
        assertThat(back.ruleId).isNull()
    }

    @Test
    fun currentRuleValueWinsThenStoredLabelThenId() {
        assertThat(DecisionRuleText.ruleLabel(block(5, "old"), listOf(rule))).isEqualTo("lottery")
        assertThat(DecisionRuleText.ruleLabel(block(5, "old"))).isEqualTo("old")
        assertThat(DecisionRuleText.ruleLabel(block(5))).isEqualTo("#5")
    }

    @Test
    fun onlyBlockRuleEntriesAreNamed() {
        assertThat(DecisionRuleText.ruleLabel(block(null))).isNull()
        assertThat(DecisionRuleText.ruleLabel(block(5, reason = "UNKNOWN_SENDER"))).isNull()
        assertThat(DecisionRuleText.ruleLabel(block(5).copy(decision = "ALLOW"))).isNull()
    }

    @Test
    fun allowRulesAreNamedToo() {
        val allow = SpamLogEntry(1, "+15551234567", 1, "ALLOW", "ALLOW_RULE", "SMS", 6, "+1555")
        val allowRule = SpamRule(id = 6, kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "+15551234567")
        assertThat(DecisionRuleText.ruleLabel(allow)).isEqualTo("+1555")
        assertThat(DecisionRuleText.ruleLabel(allow, listOf(allowRule))).isEqualTo("+15551234567")
        // Entries written before allow decisions kept their rule have no id and keep the plain reason.
        assertThat(DecisionRuleText.ruleLabel(allow.copy(ruleId = null))).isNull()
        assertThat(DecisionRuleText.ruleLabel(allow.copy(reason = "CONTACT"))).isNull()
    }

    @Test
    fun longValuesAreShortened() {
        val long = rule.copy(pattern = "a".repeat(80))
        assertThat(DecisionRuleText.label(long)).hasLength(DecisionRuleText.MAX_LABEL)
        assertThat(DecisionRuleText.label(long)).endsWith("…")
    }
}
