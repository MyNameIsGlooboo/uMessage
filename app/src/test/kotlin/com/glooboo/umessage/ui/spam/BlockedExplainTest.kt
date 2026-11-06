package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-16 and ID-52: the held-because line and the test verdict. */
class BlockedExplainTest {

    private fun msg(body: String, reason: DecisionReason = DecisionReason.UNKNOWN_SENDER) =
        BlockedMessage(1, "VM-BANK", body, 1, false, 1000L, reason)

    @Test
    fun linkIsTheReasonWhenTheTextHasOne() {
        val e = BlockedExplain.of(msg("Your code is 482913 http://x.example"))!!
        assertThat(e.why).isEqualTo(HeldWhy.UNKNOWN_WITH_LINK)
    }

    @Test
    fun plainUnknownSenderHasNoLinkReason() {
        val e = BlockedExplain.of(msg("Your code is 482913"))!!
        assertThat(e.why).isEqualTo(HeldWhy.UNKNOWN)
        assertThat(e.code).isEqualTo("482913")
    }

    @Test
    fun placeholdersAndOtherReasonsAreNotExplained() {
        assertThat(BlockedExplain.of(msg(""))).isNull()
        assertThat(BlockedExplain.of(msg("hi", DecisionReason.MODE_OFF))).isNull()
    }

    @Test
    fun ruleHoldsGetTheirOwnExplanationSoTheyCanBeReleased() {
        // Bug 13: in a group, the message a rule held needs its own Release once and Copy code.
        val e = BlockedExplain.of(msg("free code 482913", DecisionReason.BLOCK_RULE))!!
        assertThat(e.why).isEqualTo(HeldWhy.RULE)
        assertThat(e.code).isEqualTo("482913")
    }

    @Test
    fun verdictFollowsTheDecision() {
        assertThat(Verdict.of(FilterDecision.Block(DecisionReason.BLOCK_RULE, 3), "x")).isEqualTo(Verdict.HELD_BY_RULE)
        assertThat(Verdict.of(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), "see http://a.example"))
            .isEqualTo(Verdict.HELD_UNKNOWN_WITH_LINK)
        assertThat(Verdict.of(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), "hello"))
            .isEqualTo(Verdict.HELD_UNKNOWN)
        assertThat(Verdict.of(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), "")).isEqualTo(Verdict.ALLOWED_CODE)
        assertThat(Verdict.of(FilterDecision.Allow(DecisionReason.CONTACT), "")).isEqualTo(Verdict.ALLOWED_CONTACT)
        assertThat(Verdict.HELD_UNKNOWN.held).isTrue()
        assertThat(Verdict.ALLOWED_CODE.held).isFalse()
    }
}
