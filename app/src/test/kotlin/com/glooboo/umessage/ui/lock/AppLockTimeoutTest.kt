package com.glooboo.umessage.ui.lock

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.commons.helpers.AppLockManager
import org.fossify.commons.helpers.BaseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The "lock after leaving" choice, through the real commons lock manager and config. */
@RunWith(AndroidJUnit4::class)
class AppLockTimeoutTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val config = BaseConfig.newInstance(app)
    private var clock = 10_000_000L
    private val owner = object : LifecycleOwner {
        override val lifecycle get() = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        config.isAppPasswordProtectionOn = true
    }

    private fun manager(timeoutMs: Long): AppLockManager {
        config.unlockTimeoutDurationMs = timeoutMs
        return AppLockManager(app, config) { clock }.also { it.unlock() }
    }

    /** The user leaves the app, comes back [awayMs] later, and a screen asks whether it is locked. */
    private fun AppLockManager.awayFor(awayMs: Long): Boolean {
        onStop(owner)
        clock += awayMs
        onResume(owner)
        return isLocked()
    }

    @Test
    fun `immediately locks after any time away`() {
        assertTrue(manager(AppLockTimeouts.IMMEDIATELY).awayFor(1))
    }

    @Test
    fun `a fresh unlock is not undone straight away, even with immediately`() {
        val lock = manager(AppLockTimeouts.IMMEDIATELY)
        lock.onStop(owner)
        clock += 5_000
        lock.unlock()
        clock += 3
        lock.onResume(owner)
        assertFalse(lock.isLocked())
    }

    @Test
    fun `one minute keeps it open for a minute`() {
        assertFalse(manager(60_000).awayFor(59_000))
        assertTrue(manager(60_000).awayFor(61_000))
    }

    @Test
    fun `the default stays thirty seconds`() {
        assertEquals(30_000L, BaseConfig.newInstance(ApplicationProvider.getApplicationContext()).unlockTimeoutDurationMs)
        assertFalse(manager(30_000).awayFor(29_000))
        assertTrue(manager(30_000).awayFor(31_000))
    }

    @Test
    fun `coming back from the app's own file picker does not lock, even with immediately`() {
        val lock = manager(AppLockTimeouts.IMMEDIATELY)
        lock.allowOwnTrip()
        clock += 200
        assertFalse(lock.awayFor(20_000))
        // Both the process and the screen check on the way back; neither locks.
        clock += 5
        assertFalse(lock.isLocked())
    }

    @Test
    fun `the picker exemption covers one trip only`() {
        val lock = manager(AppLockTimeouts.IMMEDIATELY)
        lock.allowOwnTrip()
        assertFalse(lock.awayFor(1_000))
        assertTrue(lock.awayFor(1_000))
    }

    @Test
    fun `a long stay in the picker locks`() {
        val lock = manager(AppLockTimeouts.IMMEDIATELY)
        lock.allowOwnTrip()
        assertTrue(lock.awayFor(AppLockManager.OWN_TRIP_MAX_MS + 1))
    }

    @Test
    fun `a picker that never opened does not excuse a later exit`() {
        val lock = manager(AppLockTimeouts.IMMEDIATELY)
        lock.allowOwnTrip()
        clock += AppLockManager.OWN_TRIP_START_MS + 1
        assertTrue(lock.awayFor(1_000))
    }

    @Test
    fun `lock now locks at once`() {
        val lock = manager(300_000)
        lock.lock()
        assertTrue(lock.isLocked())
    }

    @Test
    fun `a clock that went backwards locks`() {
        assertTrue(AppLockManager.isUnlockExpired(nowMs = 1_000, lastUnlockMs = 5_000, timeoutMs = 300_000))
        assertFalse(AppLockManager.isUnlockExpired(nowMs = 5_000, lastUnlockMs = 5_000, timeoutMs = 0))
    }

    @Test
    fun `choices are immediately, 30 seconds, 1 minute and 5 minutes, and odd values snap to one`() {
        assertEquals(listOf(0L, 30_000L, 60_000L, 300_000L), AppLockTimeouts.choices)
        assertEquals(30_000L, AppLockTimeouts.closest(30_000))
        assertEquals(60_000L, AppLockTimeouts.closest(50_000))
        assertEquals(300_000L, AppLockTimeouts.closest(3_600_000))
        assertEquals(0L, AppLockTimeouts.closest(-1))
    }
}
