package com.glooboo.umessage.ui.thread

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/** Voice typing (I40): the system speech recognizer's text goes into the message field; the user presses Send. */
object VoiceTyping {

    fun recognizeIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)

    fun isAvailable(context: Context): Boolean =
        recognizeIntent("").resolveActivity(context.packageManager) != null

    /** The app language when one is chosen (the first of [appTags]), else the phone's. */
    fun languageTag(appTags: String, phoneTag: String): String =
        appTags.substringBefore(',').trim().ifEmpty { phoneTag }

    /** What goes in at the cursor: the spoken text, with a space first when the text before it does not end in one. */
    fun textToInsert(before: CharSequence, spoken: String): String {
        val words = spoken.trim()
        if (words.isEmpty()) return ""
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $words" else words
    }
}
