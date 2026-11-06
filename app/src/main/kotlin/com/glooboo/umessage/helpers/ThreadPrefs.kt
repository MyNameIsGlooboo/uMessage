package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Display settings of the thread screen, in their own preferences file (`Config` keeps its preferences private).
 * [useJalaliDates]: date separators and the schedule summary use the Jalali calendar (TH-03, TH-24). Off by default.
 */
class ThreadPrefs private constructor(private val prefs: SharedPreferences) {

    var useJalaliDates: Boolean
        get() = prefs.getBoolean(KEY_JALALI, false)
        set(value) = prefs.edit { putBoolean(KEY_JALALI, value) }

    /** I07: a link the checks call risky is not opened from the link sheet. Off by default. */
    var riskyLinksLocked: Boolean
        get() = prefs.getBoolean(KEY_RISKY_LINKS_LOCKED, false)
        set(value) = prefs.edit { putBoolean(KEY_RISKY_LINKS_LOCKED, value) }

    /** B12: the "Send at the exact time?" dialog was shown once; later schedules go ahead without it. */
    var exactAlarmAsked: Boolean
        get() = prefs.getBoolean(KEY_EXACT_ALARM_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_EXACT_ALARM_ASKED, value) }

    /** I04: ids of the conversations whose "Not in your contacts" card was dismissed. */
    private val dismissedUnknownCards: Set<String>
        get() = prefs.getStringSet(KEY_UNKNOWN_CARD_DISMISSED, emptySet()).orEmpty()

    fun isUnknownCardDismissed(threadId: Long): Boolean = threadId.toString() in dismissedUnknownCards

    fun dismissUnknownCard(threadId: Long) =
        prefs.edit { putStringSet(KEY_UNKNOWN_CARD_DISMISSED, dismissedUnknownCards + threadId.toString()) }

    companion object {
        private const val PREFS_NAME = "spamless_thread"
        private const val KEY_JALALI = "use_jalali_dates"
        private const val KEY_EXACT_ALARM_ASKED = "exact_alarm_asked"
        private const val KEY_UNKNOWN_CARD_DISMISSED = "unknown_card_dismissed"
        private const val KEY_RISKY_LINKS_LOCKED = "risky_links_locked"

        fun get(context: Context): ThreadPrefs =
            ThreadPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.threadPrefs: ThreadPrefs get() = ThreadPrefs.get(this)
