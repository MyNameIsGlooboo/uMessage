package com.glooboo.umessage.activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper

/**
 * N6: the notification "Copy code" action. An invisible activity that the action starts directly (Android 12+ allows
 * that from a notification action; no trampoline through a receiver). It runs in its own empty task, so it never
 * brings uMessage's screens (or the app-lock PIN screen, see AppLockGuard) to the front.
 *
 * It copies while it is the focused foreground window: MIUI / HyperOS block or silently drop clipboard writes from
 * background apps (the old broadcast receiver), and some OEMs only accept them from the focused window. If focus never
 * comes, a short fallback copies anyway. Then it finishes with no animation.
 */
class CopyCodeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private val fallback = Runnable { copyAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra(EXTRA_CODE).isNullOrEmpty()) {
            done = true
            finishQuietly()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!done) handler.postDelayed(fallback, FOCUS_FALLBACK_MS)
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
        if (done) return
        done = true
        handler.removeCallbacks(fallback)
        CopyCode.copy(this, intent?.getStringExtra(EXTRA_CODE).orEmpty())
        finishQuietly()
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

        /** How long to wait for window focus before copying anyway. */
        const val FOCUS_FALLBACK_MS = 400L

        /** The intent the notification action starts; explicit, so only this app's activity gets the code. */
        fun intent(context: Context, code: String): Intent =
            Intent(context, CopyCodeActivity::class.java)
                .putExtra(EXTRA_CODE, code)
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

    /** ClipDescription.EXTRA_IS_SENSITIVE, a constant of API 33. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** Copies [code] (marked sensitive on Android 13+) and shows a toast where the system shows no confirmation. */
    fun copy(context: Context, code: String): Boolean {
        if (code.isEmpty()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(context.getString(R.string.onb_notif_clip_label), code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        try {
            clipboard.setPrimaryClip(clip)
        } catch (_: SecurityException) {
            // Some OEM ROMs refuse the write outright; the missing toast is better than a crash.
            return false
        } catch (_: IllegalStateException) {
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
