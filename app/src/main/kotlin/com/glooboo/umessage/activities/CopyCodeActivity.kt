package com.glooboo.umessage.activities

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.SensitiveClip

/**
 * N6: the notification "Copy code" action. An invisible activity that the action starts directly (Android 12+ allows
 * that from a notification action; no trampoline through a receiver). It runs in its own empty task, so it never
 * brings uMessage's screens (or the app-lock PIN screen, see AppLockGuard) to the front.
 *
 * It copies while it is the focused foreground window: MIUI / HyperOS block or silently drop clipboard writes from
 * background apps (the old broadcast receiver), and some OEMs only accept them from the focused window. If focus never
 * comes, a short fallback copies anyway. Then it finishes with no animation.
 *
 * It is also what a tap on a notification that holds only a code starts ([EXTRA_NOTIFICATION_ID]: the notification is
 * cancelled once the code is copied). With [EXTRA_REQUIRE_UNLOCK] (the "Hide codes on the lock screen" setting) it
 * never shows over the lock screen: on a locked phone it asks the system to unlock first and copies only after the
 * unlock, so a locked screen cannot leak the code. If the unlock is cancelled nothing is copied.
 */
class CopyCodeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var waitingForUnlock = false
    private val fallback = Runnable { copyAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra(EXTRA_CODE).isNullOrEmpty()) {
            done = true
            finishQuietly()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_REQUIRE_UNLOCK, false) == true) requireUnlock()
    }

    /** Keeps the activity off the lock screen and asks for the unlock; [copyAndFinish] waits for it. */
    private fun requireUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(false)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) return
        waitingForUnlock = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    waitingForUnlock = false
                    if (hasWindowFocus()) copyAndFinish() else handler.postDelayed(fallback, FOCUS_FALLBACK_MS)
                }

                override fun onDismissCancelled() = giveUp()

                override fun onDismissError() = giveUp()
            },
        )
    }

    /** The unlock did not happen: nothing is copied. */
    private fun giveUp() {
        waitingForUnlock = false
        done = true
        finishQuietly()
    }

    override fun onResume() {
        super.onResume()
        if (!done && !waitingForUnlock) handler.postDelayed(fallback, FOCUS_FALLBACK_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) copyAndFinish()
    }

    override fun onPause() {
        handler.removeCallbacks(fallback)
        // Leaving before focus or the fallback (another app came forward): still copy, then go away.
        copyAndFinish()
        super.onPause()
    }

    private fun copyAndFinish() {
        if (done || waitingForUnlock) return
        done = true
        handler.removeCallbacks(fallback)
        if (CopyCode.copy(this, intent?.getStringExtra(EXTRA_CODE).orEmpty())) cancelNotification()
        finishQuietly()
    }

    /** A tap on a code notification copies the code and takes the notification away. */
    private fun cancelNotification() {
        val id = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, NO_NOTIFICATION) ?: NO_NOTIFICATION
        if (id == NO_NOTIFICATION) return
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(id) }
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_REQUIRE_UNLOCK = "extra_require_unlock"
        private const val NO_NOTIFICATION = Int.MIN_VALUE

        /** How long to wait for window focus before copying anyway. */
        const val FOCUS_FALLBACK_MS = 400L

        /**
         * The intent a notification starts; explicit, so only this app's activity gets the code. [notificationId]
         * (when not null) is cancelled after the copy; [requireUnlock] makes a locked phone unlock first.
         */
        fun intent(
            context: Context,
            code: String,
            notificationId: Int? = null,
            requireUnlock: Boolean = false,
        ): Intent =
            Intent(context, CopyCodeActivity::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_REQUIRE_UNLOCK, requireUnlock)
                .apply { notificationId?.let { putExtra(EXTRA_NOTIFICATION_ID, it) } }
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                )
    }
}

/** Copies a verification code to the clipboard and confirms it; shared by [CopyCodeActivity] and the old receiver. */
object CopyCode {

    /** Copies [code] (marked sensitive on Android 13+) and shows a toast where the system shows no confirmation. */
    fun copy(context: Context, code: String): Boolean {
        if (code.isEmpty()) return false
        // Marked sensitive; the app never clears the clipboard by itself.
        if (!SensitiveClip.copy(context, context.getString(R.string.onb_notif_clip_label), code)) {
            // Some OEM ROMs refuse the write outright; the missing toast is better than a crash.
            return false
        }
        if (needsOwnToast()) {
            Toast.makeText(context.applicationContext, R.string.onb_notif_code_copied, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * Whether uMessage must confirm a copy with its own toast. Stock Android 13+ (API 33) shows a system clipboard
     * confirmation for every copy, so a toast there would say it twice. Below 13 nothing is shown, and Xiaomi's
     * MIUI / HyperOS (Xiaomi, Redmi and POCO phones) does not show the system confirmation even on 13+. Add other
     * ROMs that drop it here.
     */
    fun needsOwnToast(
        sdkInt: Int = Build.VERSION.SDK_INT,
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND,
    ): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || OemHelper.detect(manufacturer, brand) == Oem.XIAOMI
}
