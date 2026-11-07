package com.glooboo.umessage.ui.lock

import com.glooboo.umessage.dialogs.AppLockLogic
import org.fossify.commons.helpers.AppLockSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11: moving the date and time forward does not shorten the wait after wrong entries. */
class AppLockClockTest {
    private class Store(override var storedHash: String) : AppLockStore {
        override var failures = 0
        override var lastFailureMs = 0L
        override var lastFailureElapsedMs = -1L
        override var weakCredential = false
    }

    private var wall = 1_700_000_000_000L
    private var running = 5_000_000L
    private val store = Store(AppLockLogic.protect(AppLockLogic.hashPin("2580")))
    private val session = AppLockSession(store, { running }, { wall })

    private fun wrong() {
        "1111".forEach { session.addDigit(it) }
        session.submitPin()
    }

    @Test
    fun `setting the wall clock forward does not end the wait`() {
        repeat(3) { wrong() }
        val total = AppLockSecurity.lockoutSeconds(3)
        assertEquals(total, session.lockoutSeconds())

        wall += 3_600_000L // the date and time moved an hour ahead
        running += 2_000L // two real seconds passed
        assertEquals(total - 2, session.lockoutSeconds())
    }

    @Test
    fun `the wait still ends by the real time`() {
        repeat(3) { wrong() }
        val total = AppLockSecurity.lockoutSeconds(3)
        wall += total * 1000L
        running += total * 1000L
        assertEquals(0, session.lockoutSeconds())
    }

    @Test
    fun `after a restart the running clock is ignored and the wall clock decides`() {
        repeat(3) { wrong() }
        val total = AppLockSecurity.lockoutSeconds(3)
        running = 10_000L // the phone restarted: the running clock started again, below the stored reading
        wall += total * 1000L
        assertEquals(0, session.lockoutSeconds())
        assertTrue(store.lastFailureElapsedMs > running)
    }

    @Test
    fun `a correct entry forgets the stamp`() {
        repeat(3) { wrong() }
        wall += 3_600_000L
        running += 3_600_000L
        "2580".forEach { session.addDigit(it) }
        assertEquals(AppLockSession.Outcome.Unlocked, session.submitPin())
        assertEquals(-1L, store.lastFailureElapsedMs)
    }
}
