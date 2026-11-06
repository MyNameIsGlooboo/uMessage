package com.glooboo.umessage.ui.lock

import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.junit.Assert.assertEquals
import org.junit.Test

class LockFallbackTest {
    @Test
    fun `pin and pattern locks need no fallback`() {
        assertEquals(LockFallback.None, LockFallback.of(PROTECTION_PIN, "x", deviceSecure = true))
        assertEquals(LockFallback.None, LockFallback.of(PROTECTION_PATTERN, "x", deviceSecure = false))
    }

    @Test
    fun `biometrics fall back to the backup pin`() {
        assertEquals(LockFallback.Pin, LockFallback.of(PROTECTION_FINGERPRINT, "stored", deviceSecure = true))
        assertEquals(LockFallback.Pin, LockFallback.of(PROTECTION_FINGERPRINT, "stored", deviceSecure = false))
    }

    @Test
    fun `old biometric-only locks fall back to the phone screen lock, and never shut the user out`() {
        assertEquals(LockFallback.DeviceCredential, LockFallback.of(PROTECTION_FINGERPRINT, "", deviceSecure = true))
        assertEquals(LockFallback.Open, LockFallback.of(PROTECTION_FINGERPRINT, "", deviceSecure = false))
    }

    @Test
    fun `settings asks for a backup pin or a stronger credential`() {
        assertEquals(AppLockAdvice.None, AppLockAdvice.of(lockOn = false, PROTECTION_FINGERPRINT, "", weak = true))
        assertEquals(AppLockAdvice.AddBackupPin, AppLockAdvice.of(true, PROTECTION_FINGERPRINT, "", weak = false))
        assertEquals(AppLockAdvice.None, AppLockAdvice.of(true, PROTECTION_FINGERPRINT, "stored", weak = false))
        assertEquals(AppLockAdvice.ChangeWeak, AppLockAdvice.of(true, PROTECTION_PATTERN, "stored", weak = true))
        assertEquals(AppLockAdvice.None, AppLockAdvice.of(true, PROTECTION_PIN, "stored", weak = false))
    }
}
