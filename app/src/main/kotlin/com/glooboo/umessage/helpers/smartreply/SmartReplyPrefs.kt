package com.glooboo.umessage.helpers.smartreply

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The smart replies switches (I59), in their own preferences file. Both are on by default (key unset means on):
 * [enabled] for the chips in a conversation, [notificationsEnabled] for the reply buttons of the notification.
 */
class SmartReplyPrefs internal constructor(private val prefs: SharedPreferences) {

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Suggested replies as buttons of the message notification. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    /** Calls [onChange] with the new value whenever the switch changes; returns the call that stops listening. */
    fun observe(onChange: (Boolean) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) onChange(enabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFS_NAME = "spamless_smart_replies"
        private const val KEY_ENABLED = "smart_replies_enabled"
        private const val KEY_NOTIFICATIONS = "smart_replies_notifications"

        fun get(context: Context): SmartReplyPrefs =
            SmartReplyPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.smartReplyPrefs: SmartReplyPrefs get() = SmartReplyPrefs.get(this)
