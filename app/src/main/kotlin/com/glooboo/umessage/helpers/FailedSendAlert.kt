package com.glooboo.umessage.helpers

/**
 * Q13: how loud a "Message not sent" notification is. A send the user started by hand alerts every time. An automatic
 * attempt (a scheduled message or a background retry) alerts only when no such notice is showing yet, so a retry that
 * fails again updates the notice quietly instead of ringing again.
 */
object FailedSendAlert {
    /** Whether the notification may make a sound or heads-up. */
    fun shouldAlert(automatic: Boolean, alreadyShowing: Boolean): Boolean = !(automatic && alreadyShowing)
}
