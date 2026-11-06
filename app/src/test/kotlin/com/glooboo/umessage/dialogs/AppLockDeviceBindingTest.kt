package com.glooboo.umessage.dialogs

import com.glooboo.umessage.ui.lock.AppLockSession
import com.glooboo.umessage.ui.lock.AppLockStore
import org.fossify.commons.helpers.AppLockSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The stored PIN/pattern value is tied to a key that never leaves this phone (the Android Keystore on a device; a
 * plain HMAC key here), so a copied settings file cannot be brute-forced elsewhere.
 */
class AppLockDeviceBindingTest {
    private fun hmacBinding(key: String) = AppLockSecurity.DeviceBinding { data ->
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            doFinal(data)
        }
    }

    private val thisPhone = hmacBinding("this phone")
    private val otherPhone = hmacBinding("other phone")
    private val noKeystore = AppLockSecurity.DeviceBinding { null }
    private lateinit var previous: AppLockSecurity.DeviceBinding
    private val pin = AppLockLogic.hashPin("2580")

    @Before
    fun setUp() {
        previous = AppLockSecurity.deviceBinding
        AppLockSecurity.deviceBinding = thisPhone
    }

    @After
    fun tearDown() {
        AppLockSecurity.deviceBinding = previous
    }

    @Test
    fun `a new value is bound to the phone and verifies there`() {
        val stored = AppLockLogic.protect(pin)
        assertTrue(stored.startsWith("pbkdf2-sha256-device$"))
        assertTrue(AppLockLogic.verify(stored, pin))
        assertFalse(AppLockLogic.verify(stored, AppLockLogic.hashPin("0000")))
        assertEquals(stored, AppLockLogic.upgrade(stored, pin))
    }

    @Test
    fun `a copied value is useless on another phone`() {
        val stored = AppLockLogic.protect(pin)
        AppLockSecurity.deviceBinding = otherPhone
        assertFalse(AppLockLogic.verify(stored, pin))
        AppLockSecurity.deviceBinding = noKeystore
        assertFalse(AppLockLogic.verify(stored, pin))
    }

    @Test
    fun `iterations meet the minimum`() {
        val stored = AppLockLogic.protect(pin)
        assertTrue(stored.split('$')[1].toInt() >= 100_000)
    }

    @Test
    fun `without a keystore the value is plain salted pbkdf2`() {
        AppLockSecurity.deviceBinding = noKeystore
        val stored = AppLockLogic.protect(pin)
        assertTrue(stored.startsWith("pbkdf2-sha256$"))
        assertTrue(AppLockLogic.verify(stored, pin))
        // Stays as it is while there is still no keystore.
        assertEquals(stored, AppLockLogic.upgrade(stored, pin))
    }

    @Test
    fun `an existing pbkdf2 value keeps working and is bound after the next correct entry`() {
        AppLockSecurity.deviceBinding = noKeystore
        val plain = AppLockLogic.protect(pin)
        AppLockSecurity.deviceBinding = thisPhone
        assertTrue(AppLockLogic.verify(plain, pin))
        val bound = AppLockLogic.upgrade(plain, pin)
        assertTrue(bound.startsWith("pbkdf2-sha256-device$"))
        assertTrue(AppLockLogic.verify(bound, pin))
    }

    @Test
    fun `an old sha1 value keeps working and is bound after the next correct entry`() {
        val legacy = pin
        assertTrue(AppLockLogic.verify(legacy, pin))
        val bound = AppLockLogic.upgrade(legacy, pin)
        assertTrue(bound.startsWith("pbkdf2-sha256-device$"))
        assertTrue(AppLockLogic.verify(bound, pin))
    }

    @Test
    fun `the lock screen migrates a stored value on unlock`() {
        AppLockSecurity.deviceBinding = noKeystore
        val store = object : AppLockStore {
            override var storedHash = AppLockLogic.protect(pin)
            override var failures = 0
            override var lastFailureMs = 0L
            override var weakCredential = false
        }
        AppLockSecurity.deviceBinding = thisPhone
        val session = AppLockSession(store)
        "2580".forEach { session.addDigit(it) }
        assertEquals(AppLockSession.Outcome.Unlocked, session.submitPin())
        assertTrue(store.storedHash.startsWith("pbkdf2-sha256-device$"))
    }
}
