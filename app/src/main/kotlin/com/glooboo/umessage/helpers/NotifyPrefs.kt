package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * I50, I53, I27: the three optional notification extras, each one switch, all off until the owner turns them on, in
 * their own preferences file.
 *
 * - [wakeScreen]: a new message turns the screen on for a few seconds (see [ScreenWake]).
 * - [chatBubbles]: conversation notifications can float as Android bubbles (see [BubbleSupport]).
 * - [focusCodes]: a code notification asks Xiaomi's Focus notification to show the code (see [FocusNotification]).
 */
class NotifyPrefs private constructor(private val prefs: SharedPreferences) {

    var wakeScreen: Boolean
        get() = prefs.getBoolean(KEY_WAKE_SCREEN, false)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_SCREEN, value) }

    var chatBubbles: Boolean
        get() = prefs.getBoolean(KEY_CHAT_BUBBLES, false)
        set(value) = prefs.edit { putBoolean(KEY_CHAT_BUBBLES, value) }

    var focusCodes: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_CODES, false)
        set(value) = prefs.edit { putBoolean(KEY_FOCUS_CODES, value) }

    companion object {
        private const val PREFS_NAME = "spamless_notify_extras"
        private const val KEY_WAKE_SCREEN = "wake_screen"
        private const val KEY_CHAT_BUBBLES = "chat_bubbles"
        private const val KEY_FOCUS_CODES = "focus_codes"

        fun get(context: Context): NotifyPrefs =
            NotifyPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.notifyPrefs: NotifyPrefs get() = NotifyPrefs.get(this)
