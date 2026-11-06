package com.glooboo.umessage.ui.lock

import com.glooboo.umessage.dialogs.AppLockLogic
import org.fossify.commons.helpers.AppLockSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    private class FakeStore(override var storedHash: String) : AppLockStore {
        override var failures = 0
        override var lastFailureMs = 0L
        override var weakCredential = false
    }

    private var clock = 1_000_000L
    private val pinStore = FakeStore(AppLockLogic.protect(AppLockLogic.hashPin("2580")))
    private val session = AppLockSession(pinStore) { clock }

    private fun type(pin: String) = pin.forEach { session.addDigit(it) }

    @Test
    fun `correct pin unlocks and resets the count`() {
        pinStore.failures = 2
        type("2580")
        assertEquals(AppLockSession.Outcome.Unlocked, session.submitPin())
        assertEquals(0, pinStore.failures)
        assertEquals("", session.pin)
    }

    @Test
    fun `wrong pin counts and the third starts a doubling wait`() {
        type("1111")
        assertEquals(AppLockSession.Outcome.Wrong(0), session.submitPin())
        assertEquals("", session.pin)
        type("1111")
        assertEquals(AppLockSession.Outcome.Wrong(0), session.submitPin())
        type("1111")
        val third = session.submitPin()
        assertEquals(AppLockSession.Outcome.Wrong(AppLockSecurity.lockoutSeconds(3)), third)
        assertEquals(3, pinStore.failures)
        assertTrue(session.lockoutSeconds() > 0)

        // Locked out: no typing, and nothing is checked.
        assertFalse(session.addDigit('2'))
        clock += AppLockSecurity.lockoutSeconds(3) * 1000L
        assertEquals(0, session.lockoutSeconds())

        // The next wrong one waits twice as long; waiting did not reset the count.
        type("1111")
        assertEquals(AppLockSession.Outcome.Wrong(AppLockSecurity.lockoutSeconds(4)), session.submitPin())
        assertEquals(AppLockSecurity.lockoutSeconds(3) * 2, AppLockSecurity.lockoutSeconds(4))
    }

    @Test
    fun `remaining wait counts down with the clock`() {
        pinStore.failures = 3
        pinStore.lastFailureMs = clock
        val total = AppLockSecurity.lockoutSeconds(3)
        assertEquals(total, session.lockoutSeconds())
        clock += 1500
        assertEquals(total - 1, session.lockoutSeconds())
    }

    @Test
    fun `pin length is limited and delete removes the last digit`() {
        type("123456789012")
        assertEquals(AppLockLogic.MAX_PIN_LENGTH, session.pin.length)
        assertTrue(session.deleteDigit())
        assertEquals("123456789", session.pin)
        assertFalse(session.addDigit('x'))
    }

    @Test
    fun `empty entries are not attempts`() {
        assertNull(session.submitPin())
        assertNull(session.submitPattern(emptyList()))
        assertFalse(session.deleteDigit())
        assertEquals(0, pinStore.failures)
    }

    @Test
    fun `pattern verifies and an old unsalted hash is upgraded`() {
        val legacy = AppLockLogic.hashPattern(listOf(0, 1, 2, 5, 8))
        val store = FakeStore(legacy)
        val patterns = AppLockSession(store) { clock }
        assertEquals(AppLockSession.Outcome.Wrong(0), patterns.submitPattern(listOf(0, 1, 2)))
        assertEquals(legacy, store.storedHash)
        assertEquals(AppLockSession.Outcome.Unlocked, patterns.submitPattern(listOf(0, 1, 2, 5, 8)))
        assertFalse(AppLockSecurity.isLegacy(store.storedHash))
        assertTrue(AppLockSecurity.verify(store.storedHash, legacy))
    }

    @Test
    fun `unlocking with a short pattern marks the credential as weak, a long one clears it`() {
        val one = listOf(4)
        val store = FakeStore(AppLockLogic.protect(AppLockLogic.hashPattern(one)))
        val patterns = AppLockSession(store) { clock }
        assertEquals(AppLockSession.Outcome.Unlocked, patterns.submitPattern(one))
        assertTrue(store.weakCredential)

        val long = listOf(0, 1, 2, 5)
        store.storedHash = AppLockLogic.protect(AppLockLogic.hashPattern(long))
        assertEquals(AppLockSession.Outcome.Unlocked, patterns.submitPattern(long))
        assertFalse(store.weakCredential)
    }

    @Test
    fun `unlocking with a three digit pin marks it as weak`() {
        val store = FakeStore(AppLockLogic.hashPin("258"))
        val short = AppLockSession(store) { clock }
        "258".forEach { short.addDigit(it) }
        assertEquals(AppLockSession.Outcome.Unlocked, short.submitPin())
        assertTrue(store.weakCredential)
    }

    @Test
    fun `a correct entry after a long wait resets the count and the wait`() {
        repeat(5) {
            clock += AppLockSecurity.MAX_LOCKOUT_SECONDS * 1000L
            type("1111")
            session.submitPin()
        }
        assertEquals(5, pinStore.failures)
        assertEquals(AppLockSecurity.lockoutSeconds(5), session.lockoutSeconds())
        assertEquals(20, AppLockSecurity.lockoutSeconds(5))
        // While waiting even the right PIN is not checked.
        type("2580")
        assertFalse(session.pin.isNotEmpty())
        clock += 20_000
        type("2580")
        assertEquals(AppLockSession.Outcome.Unlocked, session.submitPin())
        assertEquals(0, pinStore.failures)
        assertEquals(0L, pinStore.lastFailureMs)
        assertEquals(0, session.lockoutSeconds())
    }
}
