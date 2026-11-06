package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * I02, I45, I08: what the app cleans up and reports by itself, in its own preferences file. Everything cleans up
 * nothing until the user chooses: [codeDays] and [oldMessageDays] are 0 (never) by default, and so is [digestOn] on a
 * new install (U42): no notification the user did not ask for.
 */
class CleanupPrefs private constructor(private val prefs: SharedPreferences) {

    /** I02: read verification codes older than this many days go to the recycle bin. 0 = never. */
    var codeDays: Int
        get() = prefs.getInt(KEY_CODE_DAYS, 0)
        set(value) = prefs.edit { putInt(KEY_CODE_DAYS, value) }

    /** I45: messages older than this many days go to the recycle bin. 0 = keep for ever. */
    var oldMessageDays: Int
        get() = prefs.getInt(KEY_OLD_DAYS, 0)
        set(value) = prefs.edit { putInt(KEY_OLD_DAYS, value) }

    /** The inbox card that offers [codeDays] was answered or closed. */
    var codeCardDismissed: Boolean
        get() = prefs.getBoolean(KEY_CODE_CARD_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(KEY_CODE_CARD_DISMISSED, value) }

    /** I08: a quiet weekly summary of what was blocked. Off until chosen; see [InstallDefaults] for older installs. */
    var digestOn: Boolean
        get() = prefs.getBoolean(KEY_DIGEST_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_DIGEST_ON, value) }

    /** True once the summary was switched on or off, by the user or by [InstallDefaults]. */
    val digestChosen: Boolean get() = prefs.contains(KEY_DIGEST_ON)

    /** True when the automatic cleanup is due at [now]; records the run, so receivers do not scan on every text. */
    @Synchronized
    fun claimCleanup(now: Long): Boolean {
        if (!DuePolicy.isDue(prefs.getLong(KEY_LAST_CLEANUP, 0L), now, CLEANUP_INTERVAL_MS)) return false
        prefs.edit { putLong(KEY_LAST_CLEANUP, now) }
        return true
    }

    /**
     * True when a week has passed since the last digest. The first call (and a clock that went backwards) only starts
     * the week: a new install never posts a digest at once.
     */
    @Synchronized
    fun claimDigest(now: Long): Boolean {
        val last = prefs.getLong(KEY_LAST_DIGEST, 0L)
        if (last <= 0L || now < last) {
            prefs.edit { putLong(KEY_LAST_DIGEST, now) }
            return false
        }
        if (now - last < DIGEST_INTERVAL_MS) return false
        prefs.edit { putLong(KEY_LAST_DIGEST, now) }
        return true
    }

    companion object {
        private const val PREFS_NAME = "umessage_cleanup"
        private const val KEY_CODE_DAYS = "code_cleanup_days"
        private const val KEY_OLD_DAYS = "old_message_days"
        private const val KEY_CODE_CARD_DISMISSED = "code_cleanup_card_dismissed"
        private const val KEY_DIGEST_ON = "weekly_digest_on"
        private const val KEY_LAST_CLEANUP = "last_cleanup_at"
        private const val KEY_LAST_DIGEST = "last_digest_at"

        private const val HOUR_MS = 60L * 60 * 1000
        const val CLEANUP_INTERVAL_MS = 20 * HOUR_MS
        const val DIGEST_INTERVAL_MS = 7 * 24 * HOUR_MS

        fun get(context: Context): CleanupPrefs =
            CleanupPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.cleanupPrefs: CleanupPrefs get() = CleanupPrefs.get(this)

/** When a periodic job that last ran at `last` is due again. */
object DuePolicy {
    /** Due when it never ran, the clock moved backwards past the last run, or [interval] has passed. */
    fun isDue(last: Long, now: Long, interval: Long): Boolean = last <= 0L || now < last || now - last >= interval
}
