package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I40: the microphone shows only with the setting on, an empty field and nothing attached. */
class ComposerRulesTest {

    @Test
    fun theMicrophoneShowsOnlyWhenTheSettingIsOnAndThereIsNothingToSend() {
        assertThat(showsVoiceButton(enabled = true, canSend = false)).isTrue()
        assertThat(showsVoiceButton(enabled = true, canSend = true)).isFalse()
        assertThat(showsVoiceButton(enabled = false, canSend = false)).isFalse()
    }
}
