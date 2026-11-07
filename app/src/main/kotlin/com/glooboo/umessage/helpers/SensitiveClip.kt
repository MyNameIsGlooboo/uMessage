package com.glooboo.umessage.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * I15 and I25: copies of private text. A copy is marked sensitive (Android 13+ then hides it from the clipboard
 * preview), and a copied verification code or password is removed from the clipboard again after [CLEAR_AFTER_MS], but
 * only while the clipboard still holds exactly that text, so something copied later is never wiped.
 *
 * Android lets only the app in front (or the keyboard) touch the clipboard, so the delayed clear is best effort: it
 * works while uMessage is still the app in front, and is simply skipped when the system refuses.
 */
@Suppress("TooGenericExceptionCaught")
object SensitiveClip {
    const val CLEAR_AFTER_MS = 60_000L

    /** ClipDescription.EXTRA_IS_SENSITIVE, a constant of API 33. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** A plain-text clip of [text], marked sensitive on Android 13+. */
    fun clipOf(label: CharSequence, text: String, sdkInt: Int = Build.VERSION.SDK_INT): ClipData {
        val clip = ClipData.newPlainText(label, text)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        return clip
    }

    /** True when [clip] is exactly the text [text]: the only case in which a delayed clear may wipe it. */
    fun holds(clip: ClipData?, text: String): Boolean =
        clip != null && clip.itemCount > 0 && clip.getItemAt(0).text?.toString() == text

    /** Puts [text] on the clipboard as sensitive, clears it after [clearAfterMs] unless null. False if refused. */
    fun copy(context: Context, label: CharSequence, text: String, clearAfterMs: Long? = CLEAR_AFTER_MS): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        try {
            clipboard.setPrimaryClip(clipOf(label, text))
        } catch (_: RuntimeException) {
            return false // some ROMs refuse the write outright (SecurityException); best effort only
        }
        if (clearAfterMs != null) scheduleClear(context, text, clearAfterMs)
        return true
    }

    /** Marks what was just copied through another path as sensitive, and clears it later when [clear] is true. */
    fun mark(context: Context, text: String, clear: Boolean) {
        copy(context, text, text, if (clear) CLEAR_AFTER_MS else null)
    }

    fun scheduleClear(context: Context, text: String, delayMs: Long = CLEAR_AFTER_MS) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ clearIfHolds(app, text) }, delayMs)
    }

    /** Clears the clipboard when it still holds [text]. Returns whether it did. */
    fun clearIfHolds(context: Context, text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            if (!holds(clipboard.primaryClip, text)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            true
        } catch (_: RuntimeException) {
            false // refused while another app is in front
        }
    }
}
