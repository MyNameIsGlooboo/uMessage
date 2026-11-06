package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceTypingTest {

    @Test
    fun spokenTextStartsWithoutSpaceInAnEmptyFieldOrAfterWhitespace() {
        assertThat(VoiceTyping.textToInsert("", "see you at noon")).isEqualTo("see you at noon")
        assertThat(VoiceTyping.textToInsert("Hello ", "see you")).isEqualTo("see you")
        assertThat(VoiceTyping.textToInsert("Hello\n", "see you")).isEqualTo("see you")
    }

    @Test
    fun spokenTextGetsASpaceAfterAWord() {
        assertThat(VoiceTyping.textToInsert("Hello", "see you")).isEqualTo(" see you")
        assertThat(VoiceTyping.textToInsert("Hello,", "  see you ")).isEqualTo(" see you")
    }

    @Test
    fun blankSpeechInsertsNothing() {
        assertThat(VoiceTyping.textToInsert("Hello", "   ")).isEmpty()
    }

    @Test
    fun theAppLanguageWinsOverThePhoneLanguage() {
        assertThat(VoiceTyping.languageTag("fa", "en-US")).isEqualTo("fa")
        assertThat(VoiceTyping.languageTag("pt-BR,en", "en-US")).isEqualTo("pt-BR")
        assertThat(VoiceTyping.languageTag("", "de-DE")).isEqualTo("de-DE")
    }

    @Test
    fun theMicrophoneNeedsTheSettingAndAnEmptyField() {
        assertThat(showsVoiceButton(enabled = true, canSend = false)).isTrue()
        assertThat(showsVoiceButton(enabled = true, canSend = true)).isFalse()
        assertThat(showsVoiceButton(enabled = false, canSend = false)).isFalse()
    }
}
