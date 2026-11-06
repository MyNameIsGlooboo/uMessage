package org.fossify.commons.helpers

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.fossify.commons.compose.extensions.config

/**
 * Whether the app is locked. Observes the process lifecycle: once the app has been in the background for longer than
 * [BaseConfig.unlockTimeoutDurationMs] (uMessage: chosen in Settings, 0 = lock as soon as the app is left), the next
 * check locks it.
 */
class AppLockManager(
    private val context: Application,
    private val config: BaseConfig = context.config,
    private val now: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {

    @Volatile
    private var isLocked: Boolean = config.isAppPasswordProtectionOn

    @Volatile
    private var shouldUpdateLockState: Boolean = true

    /** uMessage: when [allowOwnTrip] was called, NO_TIME otherwise. */
    @Volatile
    private var ownTripAskedMs: Long = NO_TIME

    /** uMessage: when the app was left for a screen it opened itself ([allowOwnTrip]), NO_TIME otherwise. */
    @Volatile
    private var ownTripLeftMs: Long = NO_TIME

    fun lock() {
        isLocked = true
        resetUnlockTimestamp(0L)
    }

    fun unlock() {
        isLocked = false
        // uMessage: a fresh unlock is not undone by the check that follows it (with a 0 timeout it would be, when the
        // app came back from another app, such as the system screen-lock check, just before).
        shouldUpdateLockState = false
        resetUnlockTimestamp()
    }

    /**
     * uMessage: the app is about to open a system screen of its own and wait for its result, such as the file picker
     * for a backup. Leaving the app for it does not count as leaving: coming back within [OWN_TRIP_MAX_MS] does not
     * lock, even with "lock immediately". It covers only the next time the app is left, and only when that happens
     * within [OWN_TRIP_START_MS] of this call, so a picker that never opened cannot excuse a later real exit.
     */
    fun allowOwnTrip() {
        ownTripAskedMs = now()
    }

    fun isLocked(): Boolean {
        updateLockState()
        return isLocked
    }

    override fun onResume(owner: LifecycleOwner) = updateLockState()

    override fun onStop(owner: LifecycleOwner) {
        shouldUpdateLockState = true
        val time = now()
        val asked = ownTripAskedMs
        ownTripAskedMs = NO_TIME
        ownTripLeftMs = if (!isLocked && asked != NO_TIME && time - asked in 0..OWN_TRIP_START_MS) time else NO_TIME
        if (!isLocked) {
            resetUnlockTimestamp()
        }
    }

    private fun updateLockState() {
        if (config.isAppPasswordProtectionOn) {
            val time = now()
            if (backFromOwnTrip(time)) {
                shouldUpdateLockState = false
                resetUnlockTimestamp(time)
                return
            }
            val unlockExpired = isUnlockExpired(time, config.lastUnlockTimestampMs, config.unlockTimeoutDurationMs)
            if (unlockExpired && shouldUpdateLockState) {
                isLocked = true
                shouldUpdateLockState = false
            } else {
                resetUnlockTimestamp(time)
            }
        } else {
            isLocked = false
        }
    }

    /** True once, on the first check after coming back in time from a screen the app opened itself. */
    private fun backFromOwnTrip(time: Long): Boolean {
        val left = ownTripLeftMs
        if (left == NO_TIME) return false
        ownTripLeftMs = NO_TIME
        return !isLocked && time - left in 0..OWN_TRIP_MAX_MS
    }

    private fun resetUnlockTimestamp(timestamp: Long = now()) {
        config.lastUnlockTimestampMs = timestamp
    }

    companion object {
        private const val NO_TIME = -1L

        /** uMessage: the app must be left this soon after [allowOwnTrip] for the trip to count. */
        const val OWN_TRIP_START_MS = 10_000L

        /** uMessage: the longest a trip to the app's own picker may take and still come back unlocked. */
        const val OWN_TRIP_MAX_MS = 120_000L

        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(appContext: Application): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(appContext).also { instance = it }
            }
        }

        /** True when the app left at [lastUnlockMs] has been away longer than [timeoutMs] (0: any time at all). */
        fun isUnlockExpired(nowMs: Long, lastUnlockMs: Long, timeoutMs: Long): Boolean =
            nowMs - lastUnlockMs > timeoutMs.coerceAtLeast(0L) || nowMs < lastUnlockMs
    }
}
