package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** The speech engine [MessageSpeaker] talks to; [AndroidSpeechEngine] is the real one. */
interface SpeechEngine {
    /** Starts the engine; [onReady] gets true once it can speak. */
    fun start(onReady: (Boolean) -> Unit)

    /** Whether the engine has a voice for [locale]. */
    fun hasVoice(locale: Locale): Boolean

    /** Speaks [text] in [locale]; [onDone] runs when it ends or is stopped. False when the engine refused. */
    fun speak(text: String, locale: Locale, onDone: () -> Unit): Boolean
    fun stop()
    fun shutdown()
}

/** Which language to read [text] in: Persian when it has Arabic-script letters, else the app's own language. */
fun speechLocale(text: String, appLocale: Locale): Locale =
    if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }) {
        Locale.forLanguageTag("fa")
    } else {
        appLocale
    }

/** Codes are never read aloud while "Hide codes" is on: speaking one in public would show it as much as the screen. */
fun canReadAloud(body: String, hideCodesOn: Boolean, hasCode: Boolean): Boolean =
    body.isNotBlank() && !(hideCodesOn && hasCode)

/**
 * Reads one message at a time aloud (I49). The engine starts on the first use and is shut down by [release]. Speaking
 * the message that is already being read stops it. [onNoVoice] gets the language that has no voice installed.
 */
class MessageSpeaker(
    private val newEngine: () -> SpeechEngine,
    private val appLocale: () -> Locale,
    private val onNoVoice: (Locale) -> Unit,
    private val onFailed: () -> Unit,
    /** Called with the id being read, or null when nothing is. */
    private val onSpeakingChanged: (Long?) -> Unit = {},
) {
    private var engine: SpeechEngine? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    var speakingId: Long? = null
        private set

    fun toggle(id: Long, text: String) {
        if (speakingId == id) {
            stop()
            return
        }
        val locale = speechLocale(text, appLocale())
        whenReady { engine ->
            if (!engine.hasVoice(locale)) {
                onNoVoice(locale)
                return@whenReady
            }
            setSpeaking(id)
            val started = engine.speak(text, locale) { if (speakingId == id) setSpeaking(null) }
            if (!started) {
                setSpeaking(null)
                onFailed()
            }
        }
    }

    fun stop() {
        engine?.stop()
        setSpeaking(null)
    }

    fun release() {
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
        setSpeaking(null)
    }

    private fun setSpeaking(id: Long?) {
        if (speakingId == id) return
        speakingId = id
        onSpeakingChanged(id)
    }

    private fun whenReady(action: (SpeechEngine) -> Unit) {
        val current = engine
        if (current != null && ready) {
            action(current)
            return
        }
        pending = { engine?.let(action) }
        if (current != null) return
        val created = newEngine()
        engine = created
        created.start { ok ->
            if (engine !== created) return@start
            ready = ok
            val run = pending
            pending = null
            if (ok) {
                run?.invoke()
            } else {
                created.shutdown()
                engine = null
                onFailed()
            }
        }
    }
}

/** [SpeechEngine] on android.speech.tts.TextToSpeech: offline voices, no permission and no network of our own. */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private var onDone: (() -> Unit)? = null
    private var current: String? = null
    private var utterances = 0

    override fun start(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post { onReady(status == TextToSpeech.SUCCESS) }
        }
    }

    override fun hasVoice(locale: Locale): Boolean =
        (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    override fun speak(text: String, locale: Locale, onDone: () -> Unit): Boolean {
        val engine = tts ?: return false
        this.onDone = onDone
        // Each text has its own id: the end of the text it replaces must not count as the end of this one.
        val id = UTTERANCE_PREFIX + (++utterances)
        current = id
        engine.language = locale
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        return engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, id) ==
            TextToSpeech.SUCCESS
    }

    private fun finished(utteranceId: String?) {
        main.post { if (utteranceId == current) onDone?.invoke() }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        onDone = null
    }

    private companion object {
        const val UTTERANCE_PREFIX = "umessage_read_aloud_"
    }
}
