package com.glooboo.umessage.ui.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** N5: Allowed is never drawn in primary, which is red in the Coral preset and looked like Blocked. */
class DecisionToneTest {

    @Test
    fun blockedAndFailuresAreNegative() {
        listOf("BLOCK", "ERROR", "FORWARD_FAILED").forEach {
            assertThat(decisionTone(it)).isEqualTo(DecisionTone.NEGATIVE)
        }
    }

    @Test
    fun allowedAndForwardedArePositive() {
        listOf("ALLOW", "FORWARD").forEach { assertThat(decisionTone(it)).isEqualTo(DecisionTone.POSITIVE) }
    }

    @Test
    fun skippedForwardIsNeutral() {
        assertThat(decisionTone("FORWARD_SKIPPED")).isEqualTo(DecisionTone.NEUTRAL)
    }
}
