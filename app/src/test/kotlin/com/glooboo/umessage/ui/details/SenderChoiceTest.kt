package com.glooboo.umessage.ui.details

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** U15: Block, Normal and Always allow are one choice, driven by the two existing calls. */
class SenderChoiceTest {

    private fun apply(target: SenderChoice, current: SenderChoice): List<String> {
        val calls = mutableListOf<String>()
        applySenderChoice(target, current, { calls += "blocked=$it" }, { calls += "allowed=$it" })
        return calls
    }

    @Test
    fun theChoiceFollowsTheFlags() {
        assertThat(senderChoice(blocked = true, allowed = false)).isEqualTo(SenderChoice.BLOCK)
        assertThat(senderChoice(blocked = false, allowed = true)).isEqualTo(SenderChoice.ALLOW)
        assertThat(senderChoice(blocked = false, allowed = false)).isEqualTo(SenderChoice.NORMAL)
        assertThat(senderChoice(blocked = false, allowed = null)).isEqualTo(SenderChoice.NORMAL)
    }

    @Test
    fun blockingOrAllowingNeedsOneCall() {
        assertThat(apply(SenderChoice.BLOCK, SenderChoice.ALLOW)).containsExactly("blocked=true")
        assertThat(apply(SenderChoice.ALLOW, SenderChoice.BLOCK)).containsExactly("allowed=true")
        assertThat(apply(SenderChoice.ALLOW, SenderChoice.NORMAL)).containsExactly("allowed=true")
    }

    @Test
    fun normalUndoesWhicheverIsOn() {
        assertThat(apply(SenderChoice.NORMAL, SenderChoice.BLOCK)).containsExactly("blocked=false")
        assertThat(apply(SenderChoice.NORMAL, SenderChoice.ALLOW)).containsExactly("allowed=false")
    }

    @Test
    fun choosingTheCurrentOneDoesNothing() {
        assertThat(apply(SenderChoice.BLOCK, SenderChoice.BLOCK)).isEmpty()
    }
}
