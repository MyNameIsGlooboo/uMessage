package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadFlashTest {

    @Test
    fun theTintIsSubtleAndLastsAboutOneAndAHalfSeconds() {
        assertThat(ThreadFlash.DURATION_MS).isEqualTo(1_500L)
        assertThat(ThreadFlash.targetAlpha(true)).isAtMost(0.3f)
        assertThat(ThreadFlash.targetAlpha(true)).isGreaterThan(0f)
        assertThat(ThreadFlash.targetAlpha(false)).isEqualTo(0f)
    }

    @Test
    fun withAnimationsOffTheTintAppearsAndGoesAtOnce() {
        assertThat(ThreadFlash.fadeMillis(active = true, reduceMotion = true)).isEqualTo(0)
        assertThat(ThreadFlash.fadeMillis(active = false, reduceMotion = true)).isEqualTo(0)
        assertThat(ThreadFlash.fadeMillis(active = false, reduceMotion = false)).isGreaterThan(0)
    }

    @Test
    fun aJumpFlashesButTheInChatSearchDoesNot() {
        val key = com.glooboo.umessage.models.MessageKey(5L, false)
        assertThat(ScrollCommand.ToMessage(key).flash).isFalse()
        assertThat(ScrollCommand.ToMessage(key, flash = true).flash).isTrue()
    }
}
