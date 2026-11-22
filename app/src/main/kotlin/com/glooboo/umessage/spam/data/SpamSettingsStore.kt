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

class SpamSettingsStore(
    context: Context,
    /**
     * The old Fossify "block unknown numbers" switch. Read only to derive the filter switch while its key is missing
     * (7.1: Off with that switch on becomes on). Never throws to callers.
     */
    private val legacyBlockUnknown: () -> Boolean = { false },
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): SpamSettings {
        val defaults = SpamSettings()
        val mode = runCatching { UnknownSenderMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(defaults.mode)
        return SpamSettings(
            mode = mode,
            purgeAfterDays = prefs.getInt(KEY_PURGE_DAYS, defaults.purgeAfterDays).let(::mappedPurgeDays),
            notifyOnBlock = prefs.getBoolean(KEY_NOTIFY, defaults.notifyOnBlock),
            unknownInboxNotify = runCatching {
                UnknownInboxNotify.valueOf(prefs.getString(KEY_UNKNOWN_NOTIFY, null).orEmpty())
            }.getOrDefault(defaults.unknownInboxNotify),
            openLinkAction = prefs.getBoolean(KEY_OPEN_LINK, defaults.openLinkAction),
            blockOutsideRegion = prefs.getString(KEY_OUTSIDE_REGION, null).orEmpty(),
            filterOn = if (prefs.contains(KEY_FILTER_ON)) {
                prefs.getBoolean(KEY_FILTER_ON, defaults.filterOn)
            } else {
                filterOnFromMode(prefs.getString(KEY_MODE, null), legacySwitchOn())
            },
            codeGroupOn = prefs.getBoolean(KEY_CODE_GROUP_ON, defaults.codeGroupOn),
            wroteGroupOn = prefs.getBoolean(KEY_WROTE_GROUP_ON, defaults.wroteGroupOn),
        )
    }

    /** One lock for every store of these prefs (A-4): several parts of the app make their own store. */
    fun update(transform: (SpamSettings) -> SpamSettings) = synchronized(LOCK) {
        val current = get()
        val next = keptInStep(current, transform(current))
        prefs.edit()
            .putString(KEY_MODE, next.mode.name)
            .putInt(KEY_PURGE_DAYS, next.purgeAfterDays)
            .putBoolean(KEY_NOTIFY, next.notifyOnBlock)
            .putString(KEY_UNKNOWN_NOTIFY, next.unknownInboxNotify.name)
            .putBoolean(KEY_OPEN_LINK, next.openLinkAction)
            .putString(KEY_OUTSIDE_REGION, next.blockOutsideRegion)
            .putBoolean(KEY_FILTER_ON, next.filterOn)
            .putBoolean(KEY_CODE_GROUP_ON, next.codeGroupOn)
            .putBoolean(KEY_WROTE_GROUP_ON, next.wroteGroupOn)
            .apply()
    }

    /**
     * The one-time settings step of the provider-truth update (7.1), run by the first-run runner before anything is
     * sorted: the filter switch is written as [get] reads it (from the old mode), and auto-delete of Spam becomes
     * "keep forever" (0) on every updated install. Later runs do nothing, so a value the user chose afterwards stays.
     * Returns true when this call did the step. The old mode key stays on disk.
     */
    fun applyProviderTruthMapping(): Boolean = synchronized(LOCK) {
        if (prefs.getBoolean(KEY_MAPPED, false)) return false
        val filterOn = get().filterOn
        prefs.edit(commit = true) {
            putBoolean(KEY_FILTER_ON, filterOn)
            putInt(KEY_PURGE_DAYS, 0)
            putBoolean(KEY_MAPPED, true)
        }
        true
    }

    /**
     * Records a purge run at [now] and returns true when one is due under [PurgePolicy]; false when the last
     * run was too recent. Keeps the receivers from running a DELETE on every message.
     */
    fun claimPurge(now: Long): Boolean = synchronized(LOCK) {
        val last = prefs.getLong(KEY_LAST_PURGE, 0L)
        if (!PurgePolicy.isDue(last, now)) return false
        prefs.edit { putLong(KEY_LAST_PURGE, now) }
        true
    }

    /** Gives back a [claimPurge] whose purge failed, so the next receiver tries again, not after a full interval. */
    fun releasePurge() = synchronized(LOCK) {
        prefs.edit { remove(KEY_LAST_PURGE) }
    }

    fun observe(): Flow<SpamSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_LAST_PURGE) trySend(get())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(get())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun legacySwitchOn(): Boolean = runCatching { legacyBlockUnknown() }.getOrDefault(false)

    /**
     * S5: auto-delete reads "keep forever" until the one-time step has run, so nothing the old 30 days would delete
     * goes before the messages are moved to the phone's store.
     */
    private fun mappedPurgeDays(stored: Int): Int =
        if (!prefs.getBoolean(KEY_MAPPED, false)) 0 else stored

    companion object {
        private const val PREFS_NAME = "spamless_spam_settings"

        /** Shared by every instance, as they all write the same prefs file. */
        private val LOCK = Any()
        private const val KEY_MODE = "mode"
        private const val KEY_PURGE_DAYS = "purge_after_days"
        private const val KEY_NOTIFY = "notify_on_block"
        private const val KEY_UNKNOWN_NOTIFY = "unknown_inbox_notify"
        private const val KEY_OPEN_LINK = "open_link_action"
        private const val KEY_OUTSIDE_REGION = "block_outside_region"
        private const val KEY_LAST_PURGE = "last_purge_at"

        /** Provider truth: the one filter switch (7.1). Missing on installs from before it: read from [KEY_MODE]. */
        private const val KEY_FILTER_ON = "filter_on"
        private const val KEY_CODE_GROUP_ON = "code_group_on"
        private const val KEY_WROTE_GROUP_ON = "wrote_group_on"

        /** Set by [applyProviderTruthMapping]. */
        private const val KEY_MAPPED = "provider_truth_mapped"

        /**
         * 7.1: the filter switch of a stored old mode. Smart, Strict, or no value (a fresh install, an unreadable
         * value): on. Off: off, unless the old Fossify "block unknown numbers" switch is on (that made Off act as
         * Strict).
         */
        fun filterOnFromMode(storedMode: String?, legacyBlockUnknown: Boolean): Boolean {
            val mode = UnknownSenderMode.entries.firstOrNull { it.name == storedMode } ?: return true
            return mode != UnknownSenderMode.OFF || legacyBlockUnknown
        }

        /**
         * Keeps the old mode and the switch in step while both are written: a change of the mode alone (the 0.4.14
         * screens, a restore of an old backup) sets the switch it maps to; a change of the switch alone sets the mode
         * older versions read (Smart for on, Off for off), so a downgrade keeps the user's choice.
         */
        internal fun keptInStep(current: SpamSettings, next: SpamSettings): SpamSettings {
            val modeChanged = next.mode != current.mode
            val switchChanged = next.filterOn != current.filterOn
            return when {
                modeChanged && !switchChanged -> next.copy(filterOn = next.mode != UnknownSenderMode.OFF)
                switchChanged && !modeChanged ->
                    next.copy(mode = if (next.filterOn) UnknownSenderMode.SMART else UnknownSenderMode.OFF)
                else -> next
            }
        }
    }
}

object PurgePolicy {
    /** Expired blocked messages are deleted at most this often from the background paths. */
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Also due when the clock moved backwards past the last run. */
    fun isDue(lastRun: Long, now: Long): Boolean = lastRun <= 0L || now < lastRun || now - lastRun >= INTERVAL_MS
}
