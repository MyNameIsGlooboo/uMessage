package com.glooboo.umessage.ui.lock

import android.content.Context
import com.glooboo.umessage.ui.settings.AppLocale
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.isBiometricAuthSupported
import org.fossify.commons.extensions.isPermanentBiometricError
import org.fossify.commons.extensions.showBiometricPrompt
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PIN

/**
 * The app lock screen, replacing the commons `AppLockActivity`. The commons base activities reach it through
 * `FossifyApp.appUnlockActivity` (see `App`), plain screens through `AppLockGuard`.
 *
 * It asks for the protection type chosen in Settings (PIN, pattern, or the system biometric prompt), checks entries
 * with the shared PBKDF2 and wait rules ([AppLockSession]) and unlocks the commons `AppLockManager`. Back leaves the
 * app and keeps it locked; it never shows the screen underneath.
 *
 * A biometric lock always has a way in when biometrics fail for good ([LockFallback]): its backup PIN, or for older
 * biometric-only locks the phone's own screen lock.
 */
@Suppress("TooManyFunctions") // Android lifecycle overrides plus one small step per unlock path.
class LockActivity : FragmentActivity() {
    private val viewModel: LockViewModel by viewModels()

    /**
     * Started for a result to confirm a sensitive change (forward rules) while the app is already open: the same
     * checks, but success only returns RESULT_OK and Back only cancels. The app's own lock state is not touched.
     */
    private val confirmOnly: Boolean
        get() = intent?.getBooleanExtra(EXTRA_CONFIRM, false) == true || forHiddenChats

    /** I44: asked for by a screen locked on its own (the Blocked tab): success unlocks the app, Back only cancels. */
    private val forPart: Boolean get() = intent?.getBooleanExtra(EXTRA_PART, false) == true

    /** B06: a confirmation that, once passed, also reveals the hidden chats for the session ([HiddenChatsSession]). */
    private val forHiddenChats: Boolean get() = intent?.getBooleanExtra(EXTRA_HIDDEN, false) == true

    private val screenLock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) viewModel.screenLockConfirmed()
    }

    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        fadeIn()
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        setContent {
            UMessageTheme {
                LockScreen(
                    viewModel = viewModel,
                    onBiometric = ::showBiometric,
                    onScreenLock = ::showScreenLock,
                    onLeave = ::leave,
                    title = if (confirmOnly) R.string.lock_confirm_title else R.string.lock_title,
                )
                LaunchedEffect(viewModel.unlocked) { if (viewModel.unlocked) unlock() }
                LaunchedEffect(Unit) {
                    if (viewModel.protectionType == PROTECTION_FINGERPRINT && !viewModel.biometricAutoShown) {
                        viewModel.biometricAutoShown = true
                        showBiometric()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Unlocked meanwhile (or the lock was turned off): nothing to ask for. A confirmation always asks.
        if (!confirmOnly && !appLockManager.isLocked()) finish()
    }

    /** Hardware keyboard (and the emulator's): digits, Delete and Enter work on the PIN pad. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewModel.inputType != PROTECTION_PIN) return super.onKeyDown(keyCode, event)
        val digit = event.number
        when {
            digit in '0'..'9' -> viewModel.addDigit(digit)
            keyCode == KeyEvent.KEYCODE_DEL -> viewModel.deleteDigit()
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> viewModel.confirmPin()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun fadeIn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun showBiometric() {
        if (!isBiometricAuthSupported()) {
            biometricsUnavailable()
            return
        }
        showBiometricPrompt(
            successCallback = { _, _ -> viewModel.biometricSucceeded() },
            errorCallback = { code -> if (isPermanentBiometricError(code)) biometricsUnavailable() },
        )
    }

    /** No usable biometrics: the backup PIN, or else the phone's screen lock, takes over. */
    private fun biometricsUnavailable() {
        if (!viewModel.biometricUnavailable() && viewModel.fallback == LockFallback.DeviceCredential) showScreenLock()
    }

    /**
     * The phone's own screen lock: "Forgot your PIN?" on every lock, and the way in for biometric-only locks set up
     * by older versions (no backup PIN). Without a screen lock only that older lock opens (nothing is left to check).
     */
    private fun showScreenLock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") // BiometricPrompt's device-credential mode needs Android 11; this works on all.
        val intent = if (keyguard?.isDeviceSecure == true) {
            keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.lock_screen_lock_title),
                getString(R.string.lock_screen_lock_description),
            )
        } else {
            null
        }
        if (intent != null) screenLock.launch(intent) else viewModel.fallbackSucceeded()
    }

    private fun unlock() {
        if (!confirmOnly) appLockManager.unlock()
        HiddenChatsSession.authenticated(System.currentTimeMillis())
        if (forHiddenChats) HiddenChatsSession.unlock()
        setResult(RESULT_OK)
        finish()
    }

    private fun leave() {
        if (confirmOnly || forPart) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        appLockManager.lock()
        finishAffinity()
    }

    companion object {
        /** Boolean extra: confirm the user (see [confirmOnly]) instead of unlocking the app. */
        const val EXTRA_CONFIRM = "com.glooboo.spamless.extra.LOCK_CONFIRM"

        /** Boolean extra: like [EXTRA_CONFIRM], and passing it also reveals the hidden chats for the session. */
        const val EXTRA_HIDDEN = "com.glooboo.spamless.extra.LOCK_HIDDEN"

        /** Boolean extra: unlock the app, but Back only cancels (the Blocked tab's lock; the app is open behind it). */
        const val EXTRA_PART = "com.glooboo.spamless.extra.LOCK_PART"
    }
}
