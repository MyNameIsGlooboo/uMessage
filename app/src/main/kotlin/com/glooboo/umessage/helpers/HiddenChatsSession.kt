package com.glooboo.umessage.helpers

import org.fossify.commons.helpers.AppLockManager

/**
 * B06: whether the hidden chats are unlocked right now. In memory only, so a restart hides them again. The unlock
 * lasts while the app is in use and, like the app lock, for the lock timeout after the user leaves it (0 = until the
 * app is left). It is separate from the app lock itself: the app being open does not reveal hidden chats.
 */
object HiddenChatsSession {
    private const val NONE = -1L

    /** A PIN entered this recently counts for the hidden chats too, so one entry does not ask twice in a row. */
    private const val FRESH_MS = 10_000L

    @Volatile
    private var unlocked = false

    @Volatile
    private var leftAtMs = NONE

    @Volatile
    private var authenticatedAtMs = NONE

    fun isUnlocked(): Boolean = unlocked

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    /** The user just passed the lock screen (any kind). */
    fun authenticated(nowMs: Long) {
        authenticatedAtMs = nowMs
    }

    fun recentlyAuthenticated(nowMs: Long): Boolean =
        authenticatedAtMs != NONE && nowMs - authenticatedAtMs in 0..FRESH_MS

    /** The last screen of the app went out of sight. */
    fun appLeft(nowMs: Long) {
        leftAtMs = nowMs
    }

    /** A screen of the app came back after [appLeft]: stays unlocked only within [timeoutMs] of leaving. */
    fun appReturned(nowMs: Long, timeoutMs: Long) {
        val left = leftAtMs
        leftAtMs = NONE
        if (unlocked && left != NONE && AppLockManager.isUnlockExpired(nowMs, left, timeoutMs)) unlocked = false
    }

    /** For tests: back to the state of a fresh process. */
    fun reset() {
        unlocked = false
        leftAtMs = NONE
        authenticatedAtMs = NONE
    }
}
