package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import org.fossify.commons.extensions.baseConfig

/**
 * K2-6: what the app lock's cover (M-SEC1) cannot hide. The cover makes the screen's content view invisible, but a
 * dialog or sheet draws in a window of its own, which still showed for a few frames before the lock screen came up.
 * A screen with such windows closes them when it is left while the app lock is on.
 */
internal object LockedLeave {

    /**
     * True when a screen that is being left closes its dialogs and sheets: the app lock is on, and the screen is not
     * only turning ([Activity.isChangingConfigurations]) or opening a picker of its own ([ownTrip]).
     */
    fun closesWindows(activity: Activity, ownTrip: Boolean): Boolean =
        activity.baseConfig.isAppPasswordProtectionOn && !activity.isChangingConfigurations && !ownTrip

    /**
     * Closes the dialogs that `showComposeDialog` hosts in [activity]: each host is a 0x0 ComposeView in the content,
     * and disposing it closes its dialog window at once.
     */
    fun closeHostedDialogs(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val hosts = (0 until content.childCount).map(content::getChildAt).filter(::isDialogHost)
        hosts.forEach { host ->
            (host as ComposeView).disposeComposition()
            content.removeView(host)
        }
    }

    private fun isDialogHost(view: View): Boolean =
        view is ComposeView && view.layoutParams?.let { it.width == 0 && it.height == 0 } == true
}
