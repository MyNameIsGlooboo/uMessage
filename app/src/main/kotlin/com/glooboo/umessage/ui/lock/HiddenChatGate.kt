package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession

/**
 * B06: the door of a screen that shows one chat (the thread, its details). A hidden chat opened from a notification, a
 * shortcut or a reminder, while the hidden chats are locked, asks for the app lock first and shows nothing until it is
 * passed; Back from the lock screen closes the screen. Call [check] from onResume.
 *
 * When the app lock itself still has to open (it shows before this screen), the gate waits for it instead of asking
 * twice, and a lock entered a moment ago counts for the hidden chats too ([HiddenChatsSession.recentlyAuthenticated]).
 */
class HiddenChatGate(
    private val activity: ComponentActivity,
    private val threadId: () -> Long,
) {
    /** True while the chat is covered: nothing of it may be read, marked as read or answered. */
    var blocking = false
        private set

    private var asking = false
    private var denied = false

    private val confirm =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onConfirmed)

    private fun onConfirmed(result: ActivityResult) {
        asking = false
        if (result.resultCode == Activity.RESULT_OK) {
            reveal()
        } else {
            denied = true
            activity.finish()
        }
    }

    fun check() {
        val id = threadId()
        when {
            denied -> cover()
            id == 0L || !HiddenChats.get(activity).isHidden(id) -> reveal()
            LockScope.isLockedNow(activity) -> cover()
            HiddenChatsSession.recentlyAuthenticated(System.currentTimeMillis()) -> {
                HiddenChatsSession.unlock()
                reveal()
            }
            else -> {
                cover()
                if (!asking) ask()
            }
        }
    }

    private fun ask() {
        asking = true
        confirm.launch(Intent(activity, LockActivity::class.java).putExtra(LockActivity.EXTRA_HIDDEN, true))
    }

    private fun cover() = show(false)

    private fun reveal() = show(true)

    private fun show(visible: Boolean) {
        blocking = !visible
        // The window hides from Recents while something locked is open (decided by what is unlocked right now).
        AppLockGuard.applyWindowProtection(activity)
        activity.findViewById<View>(android.R.id.content)?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}
