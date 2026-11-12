package com.glooboo.umessage.ui.lock

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.KeyguardManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.PROTECTION_PIN

/** What the line under the title says. */
enum class LockMessage { Prompt, Wrong, LockedOut, BiometricsUnavailable }

/**
 * State of the lock screen. It lives in a ViewModel so a typed PIN, a running wait and the "biometric prompt already
 * shown" flag survive rotation; nothing is saved across process death (an unfinished PIN should not be).
 */
class LockViewModel(app: Application) : AndroidViewModel(app) {
    private val config = app.baseConfig
    private val session = AppLockSession(ConfigAppLockStore(config))

    /** One of the commons PROTECTION_* values, as chosen when the lock was set up. */
    val protectionType: Int = config.appProtectionType

    /** True when the phone has a screen lock (PIN, pattern or password) that can stand in for a forgotten one. */
    val deviceSecure: Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** The way in when biometrics cannot be used; see [LockFallback]. */
    val fallback: LockFallback = LockFallback.of(protectionType, config.appPasswordHash, deviceSecure)

    /** Wrong entries left before a wait starts, shown after a wrong entry. */
    var triesLeft by mutableIntStateOf(session.triesBeforeWait())
        private set

    /** True while a biometric lock shows its backup PIN pad instead. */
    var usingPin by mutableStateOf(false)
        private set

    /** What the screen asks for now: [protectionType], or PIN while [usingPin]. */
    val inputType: Int get() = if (usingPin) PROTECTION_PIN else protectionType

    var pinLength by mutableIntStateOf(0)
        private set
    var message by mutableStateOf(LockMessage.Prompt)
        private set
    var lockoutSeconds by mutableIntStateOf(0)
        private set

    /** True while an entry is being checked (PBKDF2 takes a moment). */
    var checking by mutableStateOf(false)
        private set

    /** Bumped after every wrong entry, so the screen can shake and a drawn pattern can show red before it clears. */
    var wrongCount by mutableIntStateOf(0)
        private set

    var unlocked by mutableStateOf(false)
        private set

    /** The biometric prompt opens by itself only once, not again after rotation or after the user closed it. */
    var biometricAutoShown = false

    private var messageReset: Job? = null
    private var countdown: Job? = null

    init {
        startCountdownIfNeeded()
    }

    val inputEnabled: Boolean get() = !checking && !unlocked && lockoutSeconds == 0

    // "Wrong PIN" stays until the user types again, so it can be read.
    fun addDigit(digit: Char): Boolean {
        if (!inputEnabled || !session.addDigit(digit)) return false
        pinLength = session.pin.length
        clearWrong()
        return true
    }

    fun deleteDigit(): Boolean {
        if (!inputEnabled || !session.deleteDigit()) return false
        pinLength = session.pin.length
        clearWrong()
        return true
    }

    private fun clearWrong() {
        if (message == LockMessage.Wrong) message = LockMessage.Prompt
    }

    fun confirmPin() {
        if (!inputEnabled || session.pin.isEmpty()) return
        verify { session.submitPin() }
    }

    fun submitPattern(cells: List<Int>) {
        // A touch of 1-3 dots is not an attempt: a pattern is never shorter than the minimum,
        // so it must not count as a failure.
        if (!inputEnabled || cells.size < com.glooboo.umessage.dialogs.AppLockLogic.MIN_PATTERN_DOTS) return
        verify { session.submitPattern(cells) }
    }

    fun biometricSucceeded() {
        unlocked = true
    }

    /** Switches a biometric lock to its backup PIN; false when it has none. */
    fun usePin(): Boolean {
        if (fallback != LockFallback.Pin) return false
        usingPin = true
        return true
    }

    fun useBiometrics() {
        usingPin = false
        if (message == LockMessage.BiometricsUnavailable) message = LockMessage.Prompt
    }

    /**
     * Biometrics cannot be used (none enrolled any more, no sensor, locked for good). With a backup PIN the PIN pad
     * opens and says why; true when it did.
     */
    fun biometricUnavailable(): Boolean {
        if (!usePin()) return false
        if (message == LockMessage.Prompt) message = LockMessage.BiometricsUnavailable
        return true
    }

    /**
     * The phone's own screen lock was confirmed: "Forgot your PIN?" on any lock, or the way in for older
     * biometric-only locks. Only called with the system's confirmation result.
     */
    fun screenLockConfirmed() {
        unlocked = true
    }

    /** Older biometric-only lock on a phone with neither biometrics nor a screen lock: nothing is left to check. */
    fun fallbackSucceeded() {
        if (fallback == LockFallback.Open) unlocked = true
    }

    private fun verify(submit: () -> AppLockSession.Outcome?) {
        checking = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) { submit() }
            pinLength = session.pin.length
            checking = false
            triesLeft = session.triesBeforeWait()
            when (outcome) {
                AppLockSession.Outcome.Unlocked -> unlocked = true
                is AppLockSession.Outcome.Wrong -> {
                    if (outcome.lockoutSeconds == 0) showWrong() else startCountdownIfNeeded()
                    wrongCount++
                }
                is AppLockSession.Outcome.LockedOut -> startCountdownIfNeeded()
                null -> Unit
            }
        }
    }

    private fun showWrong() {
        messageReset?.cancel()
        message = LockMessage.Wrong
    }

    private fun startCountdownIfNeeded() {
        lockoutSeconds = session.lockoutSeconds()
        if (lockoutSeconds == 0 || countdown?.isActive == true) return
        messageReset?.cancel()
        message = LockMessage.LockedOut
        countdown = viewModelScope.launch {
            while (lockoutSeconds > 0) {
                delay(TICK_MS)
                lockoutSeconds = session.lockoutSeconds()
            }
            message = LockMessage.Prompt
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
