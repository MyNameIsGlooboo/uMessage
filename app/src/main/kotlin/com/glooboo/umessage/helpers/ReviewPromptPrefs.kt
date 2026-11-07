package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What the review prompt needs to remember, in its own preferences file: the number of different days the app was
 * opened, how many prompts were started and when the last one was. Only day numbers, counts and a time. Nothing about
 * messages or people.
 */
class ReviewPromptPrefs private constructor(private val prefs: SharedPreferences) {

    val launchDays: Int get() = prefs.getInt(KEY_LAUNCH_DAYS, 0)
    val promptsShown: Int get() = prefs.getInt(KEY_PROMPTS, 0)
    val lastPromptAt: Long get() = prefs.getLong(KEY_LAST_PROMPT, 0L)

    /** Counts today as a launch day when it differs from the last counted day (days since 1970 in UTC). */
    @Synchronized
    fun noteLaunch(now: Long) {
        val day = now / ReviewPromptPolicy.DAY_MS
        if (day == prefs.getLong(KEY_LAST_DAY, -1L)) return
        prefs.edit {
            putLong(KEY_LAST_DAY, day)
            putInt(KEY_LAUNCH_DAYS, launchDays + 1)
        }
    }

    /** Records an attempt. Called before the card is requested: Google gives no answer about the result. */
    @Synchronized
    fun recordPrompt(now: Long) {
        prefs.edit {
            putInt(KEY_PROMPTS, promptsShown + 1)
            putLong(KEY_LAST_PROMPT, now)
        }
    }

    companion object {
        private const val PREFS_NAME = "umessage_review_prompt"
        private const val KEY_LAST_DAY = "last_launch_day"
        private const val KEY_LAUNCH_DAYS = "launch_days"
        private const val KEY_PROMPTS = "prompts"
        private const val KEY_LAST_PROMPT = "last_prompt_at"

        fun get(context: Context): ReviewPromptPrefs =
            ReviewPromptPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
