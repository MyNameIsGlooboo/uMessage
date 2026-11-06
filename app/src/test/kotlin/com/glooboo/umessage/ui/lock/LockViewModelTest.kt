package com.glooboo.umessage.ui.lock

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.dialogs.AppLockLogic
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun viewModel(type: Int, hash: String): LockViewModel {
        app.baseConfig.isAppPasswordProtectionOn = true
        app.baseConfig.appProtectionType = type
        app.baseConfig.appPasswordHash = hash
        return LockViewModel(app)
    }

    @Test
    fun `biometrics that stop working switch to the backup pin`() {
        val vm = viewModel(PROTECTION_FINGERPRINT, AppLockLogic.protect(AppLockLogic.hashPin("2580")))
        assertEquals(LockFallback.Pin, vm.fallback)
        assertEquals(PROTECTION_FINGERPRINT, vm.inputType)
        assertTrue(vm.biometricUnavailable())
        assertEquals(PROTECTION_PIN, vm.inputType)
        assertEquals(LockMessage.BiometricsUnavailable, vm.message)
        // The PIN pad takes digits now.
        assertTrue(vm.addDigit('2'))
        vm.useBiometrics()
        assertEquals(PROTECTION_FINGERPRINT, vm.inputType)
        assertEquals(LockMessage.Prompt, vm.message)
    }

    @Test
    fun `an old biometric-only lock never shuts the user out`() {
        val vm = viewModel(PROTECTION_FINGERPRINT, "")
        // Robolectric's phone has no screen lock.
        assertEquals(LockFallback.Open, vm.fallback)
        assertFalse(vm.usePin())
        assertFalse(vm.biometricUnavailable())
        vm.fallbackSucceeded()
        assertTrue(vm.unlocked)
    }

    private fun LockViewModel.enter(pin: String) {
        pin.forEach { addDigit(it) }
        confirmPin()
        val until = System.currentTimeMillis() + 10_000
        while (checking && System.currentTimeMillis() < until) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `the first wrong pins say so, with the tries left, until the user types again`() {
        val vm = viewModel(PROTECTION_PIN, AppLockLogic.protect(AppLockLogic.hashPin("2580")))
        vm.enter("1111")
        assertEquals(LockMessage.Wrong, vm.message)
        assertEquals(2, vm.triesLeft)
        assertEquals(1, vm.wrongCount) // drives the shake and the reject haptic
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(LockMessage.Wrong, vm.message)
        vm.enter("1112")
        assertEquals(LockMessage.Wrong, vm.message)
        assertEquals(1, vm.triesLeft)
        assertEquals(2, vm.wrongCount)
        vm.addDigit('2')
        assertEquals(LockMessage.Prompt, vm.message)
    }

    @Test
    fun `the phone screen lock opens any lock once confirmed`() {
        val vm = viewModel(PROTECTION_PIN, AppLockLogic.protect(AppLockLogic.hashPin("2580")))
        vm.screenLockConfirmed()
        assertTrue(vm.unlocked)
    }

    @Test
    fun `pin and pattern locks cannot be skipped`() {
        val vm = viewModel(PROTECTION_PATTERN, AppLockLogic.protect(AppLockLogic.hashPattern(listOf(0, 1, 2, 5))))
        assertEquals(LockFallback.None, vm.fallback)
        assertFalse(vm.usePin())
        vm.fallbackSucceeded()
        assertFalse(vm.unlocked)
    }
}
