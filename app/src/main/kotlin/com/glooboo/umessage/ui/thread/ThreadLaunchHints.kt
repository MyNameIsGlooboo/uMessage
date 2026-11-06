package com.glooboo.umessage.ui.thread

import android.content.Intent
import com.glooboo.umessage.helpers.THREAD_FOCUS_COMPOSER
import com.glooboo.umessage.helpers.THREAD_NOTICE

/**
 * What the intent that opened a chat asks for besides the chat itself: put the cursor in the message field with the
 * keyboard up, and show a short note once. Both are taken off the intent when read, so a rotation or a return to the
 * screen does not repeat them.
 */
data class ThreadLaunchHints(val focusComposer: Boolean, val notice: String?) {

    companion object {
        fun take(intent: Intent): ThreadLaunchHints {
            val focus = intent.getBooleanExtra(THREAD_FOCUS_COMPOSER, false)
            val notice = intent.getStringExtra(THREAD_NOTICE)?.takeIf { it.isNotBlank() }
            intent.removeExtra(THREAD_FOCUS_COMPOSER)
            intent.removeExtra(THREAD_NOTICE)
            return ThreadLaunchHints(focus, notice)
        }
    }
}
