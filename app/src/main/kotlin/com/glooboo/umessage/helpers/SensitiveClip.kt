package com.glooboo.umessage.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.glooboo.umessage.R
import org.fossify.commons.extensions.toast

/**
 * I15 and I25: copies of private text are marked sensitive (Android 13+ then hides them from the clipboard preview).
 * The app never clears the clipboard by itself: a copied code stays until the user copies something else.
 */
@Suppress("TooGenericExceptionCaught")
object SensitiveClip {

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

    /** Puts [text] on the clipboard as sensitive. False if the system refused. */
    fun copy(context: Context, label: CharSequence, text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(clipOf(label, text))
            true
        } catch (_: RuntimeException) {
            false // some ROMs refuse the write outright (SecurityException); best effort only
        }
    }

    /**
     * Copies [text] as sensitive under [genericLabel], never [text] itself: the sensitive flag does not cover the
     * label, and clipboard previews and managers show it (M-SEC5). This is the only write; nothing else may put
     * [text] on the clipboard first.
     */
    fun mark(context: Context, text: String) {
        copy(context, genericLabel(context), text)
    }

    /**
     * [mark] with a confirmation that does not repeat [text]: a generic toast below Android 13, where the system shows
     * none; Android 13+ confirms copies itself. False if the system refused the write.
     */
    fun copyPrivately(context: Context, text: String, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (!copy(context, genericLabel(context), text)) return false
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) context.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
        return true
    }

    /** A label that says nothing about the copied text. */
    fun genericLabel(context: Context): String = context.getString(R.string.app_name)
}
