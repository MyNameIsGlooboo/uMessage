package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-16: the held-because line. */
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
}
