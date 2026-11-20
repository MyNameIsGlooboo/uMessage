package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.appLockManager

/**
 * U1 (provider truth): the door of a screen that shows one chat (the thread, its details) for a chat in Spam while the
 * app lock covers Spam only ([LockScope.ChosenParts]) and is locked. Such a chat counts as a locked screen, as the
 * Spam tab does: it asks for the app lock first and shows nothing until it is passed; Back from the lock screen closes
 * the screen. This closes the launcher shortcut, "recent apps", search, notifications and intents from other apps,
 * since each of them opens one of these screens. With [LockScope.WholeApp] the app lock covers every screen already.
 * Follows the pattern of [HiddenChatGate]. Call [check] from onResume, after the hidden-chat gate ([othersCover]).
 *
 * While it works out whether the chat is in Spam (a database read) the screen stays covered; a read that fails counts
 * as "in Spam", so nothing shows unasked. Nothing happens while ProviderTruthFlag is off.
 */
class SpamChatGate(
    private val activity: ComponentActivity,
    private val threadId: () -> Long,
    /** Another gate covers the screen: this one never makes it visible then. */
    private val othersCover: () -> Boolean = { false },
    private val inSpam: (Long) -> Boolean = { activity.conversationsDB.isInSpam(it) },
    private val locked: () -> Boolean = { LockScope.blockedLocked(activity) && activity.appLockManager.isLocked() },
) {
    /** True while the chat is covered: nothing of it may be read, marked as read or answered. */
    var blocking = false
        private set

    private var asking = false
    private var denied = false
    private var checks = 0

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
        if (!ProviderTruthFlag.enabled) return
        val id = threadId()
        val run = ++checks
        when {
            denied -> cover()
            id == 0L || !locked() -> reveal()
            else -> {
                cover()
                activity.lifecycleScope.launch {
                    val spam = withContext(Dispatchers.IO) { runCatching { inSpam(id) }.getOrDefault(true) }
                    // A newer check (another chat, the lock passed meanwhile) decides instead.
                    if (run != checks) return@launch
                    when {
                        !spam || !locked() -> reveal()
                        !asking -> ask()
                    }
                }
            }
        }
    }

    private fun ask() {
        asking = true
        confirm.launch(Intent(activity, LockActivity::class.java).putExtra(LockActivity.EXTRA_PART, true))
    }

    private fun cover() = show(false)

    private fun reveal() = show(true)

    private fun show(visible: Boolean) {
        val wasBlocking = blocking
        blocking = !visible
        if (!visible || wasBlocking) {
            AppLockGuard.applyWindowProtection(activity)
            val shown = visible && !othersCover()
            activity.findViewById<View>(android.R.id.content)?.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        }
    }
}
