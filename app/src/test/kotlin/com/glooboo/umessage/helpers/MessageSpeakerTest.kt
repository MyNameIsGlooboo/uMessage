package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class MessageSpeakerTest {

    private class FakeEngine(var voices: Set<String> = setOf("en", "fa"), var starts: Boolean = true) : SpeechEngine {
        var ready: ((Boolean) -> Unit)? = null
        val spoken = ArrayList<Pair<String, String>>()
        var stops = 0
        var shutdowns = 0
        var finish: (() -> Unit)? = null

        override fun start(onReady: (Boolean) -> Unit) {
            ready = onReady
        }

        fun becomeReady() = ready?.invoke(starts)
        override fun hasVoice(locale: Locale) = locale.language in voices
        override fun speak(text: String, locale: Locale, onDone: () -> Unit): Boolean {
            spoken.add(locale.language to text)
            finish = onDone
            return true
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    private val noVoice = ArrayList<String>()
    private var failed = 0
    private val changes = ArrayList<Long?>()
    private val engine = FakeEngine()

    private fun speaker(appLanguage: String = "en") = MessageSpeaker(
        newEngine = { engine },
        appLocale = { Locale.forLanguageTag(appLanguage) },
        onNoVoice = { noVoice.add(it.language) },
        onFailed = { failed++ },
        onSpeakingChanged = { changes.add(it) },
    )

    @Test
    fun persianLettersPickPersianWhateverTheAppLanguage() {
        assertThat(speechLocale("\u0633\u0644\u0627\u0645 hello", Locale.ENGLISH).language).isEqualTo("fa")
        assertThat(speechLocale("hello", Locale.ENGLISH).language).isEqualTo("en")
        assertThat(speechLocale("123 456", Locale.FRENCH).language).isEqualTo("fr")
    }

    @Test
    fun theTextIsSpokenOnceTheEngineIsReady() {
        val speaker = speaker()
        speaker.toggle(1, "hello")
        assertThat(engine.spoken).isEmpty()
        engine.becomeReady()
        assertThat(engine.spoken).containsExactly("en" to "hello")
        assertThat(speaker.speakingId).isEqualTo(1L)
    }

    @Test
    fun aSecondTapOnTheSameMessageStopsIt() {
        val speaker = speaker()
        speaker.toggle(1, "hello")
        engine.becomeReady()
        speaker.toggle(1, "hello")
        assertThat(engine.stops).isEqualTo(1)
        assertThat(speaker.speakingId).isNull()
        assertThat(engine.spoken).hasSize(1)
    }

    @Test
    fun anotherMessageReplacesTheOneBeingRead() {
        val speaker = speaker()
        speaker.toggle(1, "one")
        engine.becomeReady()
        speaker.toggle(2, "two")
        assertThat(engine.spoken.map { it.second }).containsExactly("one", "two").inOrder()
        assertThat(speaker.speakingId).isEqualTo(2L)
    }

    @Test
    fun finishingClearsTheSpeakingMessage() {
        val speaker = speaker()
        speaker.toggle(1, "hello")
        engine.becomeReady()
        engine.finish?.invoke()
        assertThat(speaker.speakingId).isNull()
        assertThat(changes).containsExactly(1L, null).inOrder()
    }

    @Test
    fun withoutAVoiceTheUserIsToldWhichLanguageAndNothingIsSpoken() {
        engine.voices = setOf("en")
        val speaker = speaker()
        speaker.toggle(1, "\u0633\u0644\u0627\u0645")
        engine.becomeReady()
        assertThat(noVoice).containsExactly("fa")
        assertThat(engine.spoken).isEmpty()
        assertThat(speaker.speakingId).isNull()
    }

    @Test
    fun anEngineThatCannotStartReportsAFailure() {
        engine.starts = false
        val speaker = speaker()
        speaker.toggle(1, "hello")
        engine.becomeReady()
        assertThat(failed).isEqualTo(1)
        assertThat(engine.shutdowns).isEqualTo(1)
        assertThat(speaker.speakingId).isNull()
    }

    @Test
    fun releaseShutsTheEngineDownAndStopsReading() {
        val speaker = speaker()
        speaker.toggle(1, "hello")
        engine.becomeReady()
        speaker.release()
        assertThat(engine.shutdowns).isEqualTo(1)
        assertThat(speaker.speakingId).isNull()
    }

    @Test
    fun codesAreNotReadWhileCodesAreHidden() {
        assertThat(canReadAloud("Your code is 482913", hideCodesOn = true, hasCode = true)).isFalse()
        assertThat(canReadAloud("Your code is 482913", hideCodesOn = false, hasCode = true)).isTrue()
        assertThat(canReadAloud("See you at 8", hideCodesOn = true, hasCode = false)).isTrue()
        assertThat(canReadAloud("  ", hideCodesOn = false, hasCode = false)).isFalse()
    }
}
