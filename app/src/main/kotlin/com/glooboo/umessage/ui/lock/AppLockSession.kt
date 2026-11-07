package com.glooboo.umessage.ui.lock

import com.glooboo.umessage.dialogs.AppLockLogic
import com.glooboo.umessage.dialogs.runningClock
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN

/** Where the lock screen reads the stored credential and keeps its wrong-entry count. */
interface AppLockStore {
    var storedHash: String
    var failures: Int
    var lastFailureMs: Long

    /** SystemClock.elapsedRealtime() at [lastFailureMs], -1 when unknown (see AppLockSecurity.remainingSeconds). */
    var lastFailureElapsedMs: Long
        get() = -1L
        set(value) = Unit

    /** True when the last correct entry was shorter than a new credential may be; Settings then suggests a change. */
    var weakCredential: Boolean
}

/** [AppLockStore] on the commons config, shared with the setup dialog and the old lock screen. */
class ConfigAppLockStore(private val config: BaseConfig) : AppLockStore {
    override var storedHash: String
        get() = config.appPasswordHash
        set(value) {
            config.appPasswordHash = value
        }
    override var failures: Int
        get() = config.passwordRetryCount
        set(value) {
            config.passwordRetryCount = value
        }
    override var lastFailureMs: Long
        get() = config.passwordCountdownStartMs
        set(value) {
            config.passwordCountdownStartMs = value
        }
    override var lastFailureElapsedMs: Long
        get() = config.passwordCountdownStartElapsedMs
        set(value) {
            config.passwordCountdownStartElapsedMs = value
        }
    override var weakCredential: Boolean
        get() = config.appLockCredentialWeak
        set(value) {
            config.appLockCredentialWeak = value
        }
}

/**
 * Rules of the unlock screen for PIN and pattern, with no Android or Compose code so they can be tested.
 *
 * Entries are reduced to the entry hash ([AppLockLogic.hashPin], [AppLockLogic.hashPattern]) and checked against the
 * stored PBKDF2 value through [AppLockLogic] (which wraps the commons `AppLockSecurity`); an old unsalted value is
 * replaced after a correct entry. The wrong-entry count and the doubling wait live in [store], so they survive
 * restarts and are shared with the setup dialog: from the 3rd wrong entry on each one starts a wait, and only a
 * correct entry resets the count.
 */
class AppLockSession(
    private val store: AppLockStore,
    private val elapsed: () -> Long = ::runningClock,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data object Unlocked : Outcome

        /** Wrong entry; [lockoutSeconds] is the wait it started, 0 when there is none yet. */
        data class Wrong(val lockoutSeconds: Int) : Outcome

        /** Still waiting from earlier wrong entries; nothing was checked. */
        data class LockedOut(val seconds: Int) : Outcome
    }

    var pin: String = ""
        private set

    /** Wrong entries still allowed before the first wait starts (0 once waits have begun). */
    fun triesBeforeWait(): Int = (AppLockLogic.MAX_RETRIES - store.failures).coerceAtLeast(0)

    /** Seconds left before another entry is accepted. */
    fun lockoutSeconds(): Int = AppLockLogic.lockoutSeconds(
        store.failures, store.lastFailureMs, now(), store.lastFailureElapsedMs, elapsed(),
    )

    /** Adds [digit] to the PIN unless it is full or input is locked out; true when it was added. */
    fun addDigit(digit: Char): Boolean {
        if (digit !in '0'..'9' || pin.length >= AppLockLogic.MAX_PIN_LENGTH || lockoutSeconds() > 0) return false
        pin += digit
        return true
    }

    fun deleteDigit(): Boolean {
        if (pin.isEmpty()) return false
        pin = pin.dropLast(1)
        return true
    }

    /** Checks the PIN typed so far and clears it, or returns null when nothing was typed. Runs PBKDF2: call off the main thread. */
    fun submitPin(): Outcome? {
        if (pin.isEmpty()) return null
        val hash = AppLockLogic.hashPin(pin)
        val weak = AppLockLogic.isWeak(PROTECTION_PIN, pin.length)
        pin = ""
        return submit(hash, weak)
    }

    /** Checks a drawn pattern ([cells] are row * 3 + column). Runs PBKDF2: call off the main thread. */
    fun submitPattern(cells: List<Int>): Outcome? =
        if (cells.isEmpty()) null else submit(AppLockLogic.hashPattern(cells), AppLockLogic.isWeak(PROTECTION_PATTERN, cells.size))

    private fun submit(entryHash: String, weak: Boolean): Outcome {
        val waiting = lockoutSeconds()
        if (waiting > 0) return Outcome.LockedOut(waiting)
        val stored = store.storedHash
        if (AppLockLogic.verify(stored, entryHash)) {
            val kept = AppLockLogic.upgrade(stored, entryHash)
            if (kept != stored) store.storedHash = kept
            store.failures = 0
            store.lastFailureMs = 0
            store.lastFailureElapsedMs = -1L
            if (store.weakCredential != weak) store.weakCredential = weak
            return Outcome.Unlocked
        }
        store.failures += 1
        if (store.failures >= AppLockLogic.MAX_RETRIES) {
            // The running-clock stamp first: the wait is judged by the smaller of the two clocks.
            store.lastFailureElapsedMs = elapsed()
            store.lastFailureMs = now()
        }
        return Outcome.Wrong(lockoutSeconds())
    }
}
