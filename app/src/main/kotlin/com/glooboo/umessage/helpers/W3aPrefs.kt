package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Settings of wave 3 (code privacy and the forwarding cost guard), in their own preferences file. Read it with [get]
 * or [w3aPrefs]; every user-facing value also comes as a [Flow].
 *
 * - [maskForwardedCodes]: a forward that carries a verification code (rules with "include codes") shows the code as
 *   `****` ([CodeMasker.ASTERISKS]); with [maskKeepLastDigits] the last two digits stay. Applies to all forward rules.
 * - [hideCodesOnLockScreen]: the lock screen shows a message that holds a code with the code hidden (`••••`), also when
 *   the lock-screen setting is "sender and message". The code is complete again once the phone is unlocked.
 * - [dailyForwardLimit]: at most this many forwards a day (0 = no limit); the rest of the day nothing is forwarded.
 * - [dailyLimitNotifiedDay]: the epoch day the "limit reached" notification was last shown (once a day).
 */
class W3aPrefs private constructor(private val prefs: SharedPreferences) {

    var maskForwardedCodes: Boolean
        get() = prefs.getBoolean(KEY_MASK_FORWARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_MASK_FORWARDED, value) }

    var maskKeepLastDigits: Boolean
        get() = prefs.getBoolean(KEY_MASK_KEEP_LAST, false)
        set(value) = prefs.edit { putBoolean(KEY_MASK_KEEP_LAST, value) }

    var hideCodesOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_HIDE_LOCK_SCREEN, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_LOCK_SCREEN, value) }

    var dailyForwardLimit: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, 0).coerceIn(0, MAX_DAILY_LIMIT)
        set(value) = prefs.edit { putInt(KEY_DAILY_LIMIT, value.coerceIn(0, MAX_DAILY_LIMIT)) }

    var dailyLimitNotifiedDay: Long
        get() = prefs.getLong(KEY_LIMIT_NOTIFIED_DAY, -1L)
        set(value) = prefs.edit { putLong(KEY_LIMIT_NOTIFIED_DAY, value) }

    val maskForwardedCodesFlow: Flow<Boolean> get() = observe { maskForwardedCodes }
    val maskKeepLastDigitsFlow: Flow<Boolean> get() = observe { maskKeepLastDigits }
    val hideCodesOnLockScreenFlow: Flow<Boolean> get() = observe { hideCodesOnLockScreen }
    val dailyForwardLimitFlow: Flow<Int> get() = observe { dailyForwardLimit }

    /** The text transformer forwards use to hide a code, or null when masking is off. */
    fun forwardCodeMasker(): ((String) -> String)? {
        if (!maskForwardedCodes) return null
        val keep = maskKeepLastDigits
        return { text -> CodeMasker.mask(text, keep, CodeMasker.ASTERISKS) }
    }

    private fun <T> observe(read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val PREFS_NAME = "spamless_w3a"
        private const val KEY_MASK_FORWARDED = "mask_forwarded_codes"
        private const val KEY_MASK_KEEP_LAST = "mask_keep_last_digits"
        private const val KEY_HIDE_LOCK_SCREEN = "hide_codes_lock_screen"
        private const val KEY_DAILY_LIMIT = "daily_forward_limit"
        private const val KEY_LIMIT_NOTIFIED_DAY = "daily_limit_notified_day"

        /** The largest daily limit the setting accepts. */
        const val MAX_DAILY_LIMIT = 1000

        fun get(context: Context): W3aPrefs =
            W3aPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.w3aPrefs: W3aPrefs get() = W3aPrefs.get(this)
