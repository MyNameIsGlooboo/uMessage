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
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.ProviderTruthFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.appLockManager
import java.util.concurrent.ConcurrentHashMap

/**
 * U1 (provider truth): the door of a screen that shows one chat (the thread, its details) for a chat in Spam while the
 * app lock covers Spam only ([LockScope.ChosenParts]) and is locked. Such a chat counts as a locked screen, as the
 * Spam tab does: it asks for the app lock first and shows nothing until it is passed; Back from the lock screen closes
 * the screen. This closes the launcher shortcut, "recent apps", search, notifications and intents from other apps,
 * since each of them opens one of these screens. With [LockScope.WholeApp] the app lock covers every screen already.
 * Follows the pattern of [HiddenChatGate]. Call [check] from onResume, after the hidden-chat gate ([othersCover]).
 *
 * Whether the chat is in Spam is a database read. C-10: a chat last seen in Messages ([Known]) is not covered while
 * that is read again, so it does not flash blank; any other chat stays covered until the read ends, and a read that
 * fails counts as "in Spam", so nothing shows unasked. While another gate covers the screen (a hidden chat asking for
 * the lock) this one waits instead of asking a second time, and a lock passed a moment ago counts. [onRevealed] runs
 * when the chat shows after it was covered (the screen marks it read then). Nothing happens while ProviderTruthFlag is
 * off.
 */
class SpamChatGate(
    private val activity: ComponentActivity,
    private val threadId: () -> Long,
    /** Another gate covers the screen: this one never makes it visible then, and does not ask while it does. */
    private val othersCover: () -> Boolean = { false },
    private val inSpam: (Long) -> Boolean = { activity.conversationsDB.isInSpam(it) },
    private val locked: () -> Boolean = { LockScope.blockedLocked(activity) && activity.appLockManager.isLocked() },
    private val recentlyPassed: () -> Boolean = {
        HiddenChatsSession.recentlyAuthenticated(System.currentTimeMillis())
    },
    private val onRevealed: () -> Unit = {},
) {
    /** True while the chat is covered: nothing of it may be read, marked as read or answered. */
    var blocking = false
        private set

    /**
     * D-8: true while it is read whether the chat is in Spam. Nothing may be marked read then, also when the chat
     * stays visible (last seen in Messages); [onRevealed] runs once it is decided that it shows.
     */
    var deciding = false
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
            onRevealed()
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
            id == 0L || isOpen() -> {
                deciding = false
                reveal()
            }
            else -> {
                // A chat known to be in Messages stays visible while that is checked again.
                if (Known.inSpam(id) != false) cover()
                deciding = true
                activity.lifecycleScope.launch { decide(id, run) }
            }
        }
    }

    /** The lock is not up for Spam, or it was passed a moment ago. */
    private fun isOpen(): Boolean = !locked() || recentlyPassed()

    private suspend fun decide(id: Long, run: Int) {
        val spam = withContext(Dispatchers.IO) { runCatching { inSpam(id) }.getOrDefault(true) }
        Known.note(id, spam)
        // A newer check (another chat, the lock passed meanwhile) decides instead.
        if (run != checks) return
        deciding = false
        if (!spam || isOpen()) {
            reveal()
            onRevealed()
            return
        }
        cover()
        // A hidden chat asks for the lock first; this gate is checked again once it is passed.
        if (!asking && !othersCover()) ask()
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

    /**
     * What the gates last read about each chat: in Spam or not. Process-wide; only a hint, every open reads again.
     * D-8: every block path notes "in Spam" at once, and a hint older than [FRESH_MS] counts for nothing (a sort may
     * have moved the chat since), so a chat that may be in Spam is covered before anything shows.
     */
    object Known {
        private const val FRESH_MS = 60_000L
        private val seen = ConcurrentHashMap<Long, Pair<Boolean, Long>>()

        fun inSpam(threadId: Long, now: Long = System.currentTimeMillis()): Boolean? =
            seen[threadId]?.takeIf { now - it.second in 0..FRESH_MS }?.first

        fun note(threadId: Long, spam: Boolean, now: Long = System.currentTimeMillis()) {
            seen[threadId] = spam to now
        }
    }
}
