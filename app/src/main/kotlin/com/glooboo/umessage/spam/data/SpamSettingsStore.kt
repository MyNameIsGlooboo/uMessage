package com.glooboo.umessage.spam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode

class SpamSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): SpamSettings {
        val defaults = SpamSettings()
        val mode = runCatching { UnknownSenderMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(defaults.mode)
        return SpamSettings(
            mode = mode,
            otpExemption = prefs.getBoolean(KEY_OTP, defaults.otpExemption),
            purgeAfterDays = prefs.getInt(KEY_PURGE_DAYS, defaults.purgeAfterDays),
            notifyOnBlock = prefs.getBoolean(KEY_NOTIFY, defaults.notifyOnBlock),
            unknownInboxNotify = runCatching {
                UnknownInboxNotify.valueOf(prefs.getString(KEY_UNKNOWN_NOTIFY, null).orEmpty())
            }.getOrDefault(defaults.unknownInboxNotify),
            openLinkAction = prefs.getBoolean(KEY_OPEN_LINK, defaults.openLinkAction),
            blockOutsideRegion = prefs.getString(KEY_OUTSIDE_REGION, null).orEmpty(),
        )
    }

    @Synchronized
    fun update(transform: (SpamSettings) -> SpamSettings) {
        val next = transform(get())
        prefs.edit()
            .putString(KEY_MODE, next.mode.name)
            .putBoolean(KEY_OTP, next.otpExemption)
            .putInt(KEY_PURGE_DAYS, next.purgeAfterDays)
            .putBoolean(KEY_NOTIFY, next.notifyOnBlock)
            .putString(KEY_UNKNOWN_NOTIFY, next.unknownInboxNotify.name)
            .putBoolean(KEY_OPEN_LINK, next.openLinkAction)
            .putString(KEY_OUTSIDE_REGION, next.blockOutsideRegion)
            .apply()
    }

    /**
     * Records a purge run at [now] and returns true when one is due under [PurgePolicy]; false when the last
     * run was too recent. Keeps the receivers from running a DELETE on every message.
     */
    @Synchronized
    fun claimPurge(now: Long): Boolean {
        val last = prefs.getLong(KEY_LAST_PURGE, 0L)
        if (!PurgePolicy.isDue(last, now)) return false
        prefs.edit { putLong(KEY_LAST_PURGE, now) }
        return true
    }

    fun observe(): Flow<SpamSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_LAST_PURGE) trySend(get())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(get())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val PREFS_NAME = "spamless_spam_settings"
        const val KEY_MODE = "mode"
        const val KEY_OTP = "otp_exemption"
        const val KEY_PURGE_DAYS = "purge_after_days"
        const val KEY_NOTIFY = "notify_on_block"
        const val KEY_UNKNOWN_NOTIFY = "unknown_inbox_notify"
        const val KEY_OPEN_LINK = "open_link_action"
        const val KEY_OUTSIDE_REGION = "block_outside_region"
        const val KEY_LAST_PURGE = "last_purge_at"
    }
}

object PurgePolicy {
    /** Expired blocked messages are deleted at most this often from the background paths. */
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Also due when the clock moved backwards past the last run. */
    fun isDue(lastRun: Long, now: Long): Boolean = lastRun <= 0L || now < lastRun || now - lastRun >= INTERVAL_MS
}
