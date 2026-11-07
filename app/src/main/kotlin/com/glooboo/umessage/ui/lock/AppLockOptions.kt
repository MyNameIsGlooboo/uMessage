package com.glooboo.umessage.ui.lock

import android.os.Build
import com.glooboo.umessage.R
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT

/**
 * How long the app stays unlocked after the user leaves it, stored in the commons
 * `BaseConfig.unlockTimeoutDurationMs`.
 *
 * The default stays 30 seconds (what every version so far did): switching to another app for a moment, to copy a code
 * or pick a photo to attach, does not ask again. Immediately is one tap away for people who want that.
 */
object AppLockTimeouts {
    const val IMMEDIATELY = 0L
    const val DEFAULT = 30_000L
    val choices = listOf(IMMEDIATELY, DEFAULT, 60_000L, 5 * 60_000L)

    /** The choice nearest to a stored [value] (older versions or other apps could have stored any number). */
    fun closest(value: Long): Long = choices.minBy { kotlin.math.abs(it - value) }

    /** The choice to show selected for a stored [value]; always one of [choices]. */
    fun selectedIndex(value: Long): Int = choices.indexOf(closest(value))
}

/**
 * What a window of the app hides while the app lock is on.
 *
 * - [hideFromRecents]: no thumbnail of the screen in the recent-apps view (`setRecentsScreenshotEnabled`, Android 13+).
 * - [secure]: FLAG_SECURE. Blocks screenshots and screen recording and also hides the Recents thumbnail on every
 *   version. Below Android 13 it is the only way to hide the thumbnail.
 *
 * - [secureWhileLeaving]: FLAG_SECURE only from the moment the screen is paused until it is resumed. Android 12 and
 *   lower have no switch for the Recents thumbnail, so with the lock on and screenshots allowed the thumbnail is made
 *   blank this way: screenshots stay possible while the app is in use, but the last chat never shows in Recents.
 *
 * "Block screenshots" is off by default (the owner's choice: screenshots stay possible, for example to report spam).
 * The Recents thumbnail is still hidden with the lock on, on every Android version.
 */
data class WindowProtection(
    val secure: Boolean,
    val hideFromRecents: Boolean,
    val secureWhileLeaving: Boolean = false,
) {
    companion object {
        val NONE = WindowProtection(secure = false, hideFromRecents = false)

        fun of(lockOn: Boolean, blockScreenshots: Boolean, sdk: Int = Build.VERSION.SDK_INT): WindowProtection = when {
            !lockOn -> NONE
            else -> {
                val canHideRecents = sdk >= Build.VERSION_CODES.TIRAMISU
                WindowProtection(
                    secure = blockScreenshots,
                    hideFromRecents = canHideRecents,
                    secureWhileLeaving = !canHideRecents && !blockScreenshots,
                )
            }
        }
    }
}

/**
 * The way into the app when biometrics cannot be used (no fingerprint or face enrolled any more, sensor broken, too
 * many failed tries), so a biometric lock never shuts the user out for good.
 */
enum class LockFallback {
    /** Not a biometric lock: PIN or pattern is the only way in already. */
    None,

    /** The backup PIN set up together with biometrics. */
    Pin,

    /** Older biometric-only locks, which have no backup PIN: the phone's own screen lock. */
    DeviceCredential,

    /**
     * Older biometric-only lock on a phone that now has no screen lock at all (removing it also removes every
     * fingerprint, and needs the old screen lock). Nothing is left to check, so the app opens, and Settings asks for
     * a PIN.
     */
    Open;

    companion object {
        fun of(protectionType: Int, storedHash: String, deviceSecure: Boolean): LockFallback = when {
            protectionType != PROTECTION_FINGERPRINT -> None
            storedHash.isNotEmpty() -> Pin
            deviceSecure -> DeviceCredential
            else -> Open
        }
    }
}

/**
 * What the dialog after setting up the lock says. Never "reinstall the app" (that deletes all its data): it points to
 * "Forgot it?" on the lock screen, which uses the phone's screen lock, and only without one says plainly what
 * forgetting would cost.
 */
@androidx.annotation.StringRes
fun lockSetupMessage(protectionType: Int, deviceSecure: Boolean): Int = when {
    !deviceSecure -> R.string.lock_setup_done_no_screen_lock
    protectionType == PROTECTION_FINGERPRINT -> R.string.lock_setup_done_biometric
    else -> R.string.lock_setup_done
}

/** What Settings suggests about the current app lock. */
enum class AppLockAdvice {
    None,

    /** The PIN or pattern is shorter than a new one may be. */
    ChangeWeak,

    /** Biometrics without a backup PIN (set up by an older version). */
    AddBackupPin;

    companion object {
        fun of(lockOn: Boolean, protectionType: Int, storedHash: String, weak: Boolean): AppLockAdvice = when {
            !lockOn -> None
            protectionType == PROTECTION_FINGERPRINT && storedHash.isEmpty() -> AddBackupPin
            weak -> ChangeWeak
            else -> None
        }
    }
}
