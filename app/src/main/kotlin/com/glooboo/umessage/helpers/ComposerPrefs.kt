package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Wave 3 composer settings, in their own preferences file (`Config` keeps its preferences private).
 * [removePhotoLocation]: pictures lose their GPS tags before sending (I09), on by default. [voiceButton]: voice typing,
 * a microphone where Send is while the field is empty and the phone has a speech recognizer (I40), on by default.
 * [signature]: one line added to sent texts, empty for none (I60). [chatSounds]: sounds for sent and received messages
 * in an open chat (I65), off by default.
 * [savedTextsJson] and [simNamesJson] belong to [SavedTexts] and [SimNames]; they are plain text on the phone.
 */
class ComposerPrefs private constructor(private val prefs: SharedPreferences) {

    var removePhotoLocation: Boolean
        get() = prefs.getBoolean(KEY_REMOVE_LOCATION, true)
        set(value) = prefs.edit { putBoolean(KEY_REMOVE_LOCATION, value) }

    var voiceButton: Boolean
        get() = prefs.getBoolean(KEY_VOICE_BUTTON, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_BUTTON, value) }

    var signature: String
        get() = prefs.getString(KEY_SIGNATURE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SIGNATURE, value) }

    var chatSounds: Boolean
        get() = prefs.getBoolean(KEY_CHAT_SOUNDS, false)
        set(value) = prefs.edit { putBoolean(KEY_CHAT_SOUNDS, value) }

    var savedTextsJson: String
        get() = prefs.getString(KEY_SAVED_TEXTS, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SAVED_TEXTS, value) }

    var simNamesJson: String
        get() = prefs.getString(KEY_SIM_NAMES, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SIM_NAMES, value) }

    companion object {
        private const val PREFS_NAME = "spamless_composer"
        private const val KEY_REMOVE_LOCATION = "remove_photo_location"
        private const val KEY_VOICE_BUTTON = "voice_button"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_CHAT_SOUNDS = "chat_sounds"
        private const val KEY_SAVED_TEXTS = "saved_texts"
        private const val KEY_SIM_NAMES = "sim_names"

        fun get(context: Context): ComposerPrefs =
            ComposerPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.composerPrefs: ComposerPrefs get() = ComposerPrefs.get(this)
