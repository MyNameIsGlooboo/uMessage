package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.ui.lock.LockScope
import org.fossify.commons.extensions.appLockManager

/**
 * Provider truth (section 5, U9): the readers outside Spam (search and its tiles, starred, notes, recycle bin, share
 * targets, the scheduled list, the export picker, clean-ups) leave every chat in Spam out. In search a Spam chat is
 * found only in the Spam section, and only while the app lock does not cover Spam (MainSearchViewModel). Nothing
 * changes while ProviderTruthFlag is off.
 */
internal object SpamReaders {

    /**
     * The chats shown in Spam (S9: a row counts only while its chat still has that sender); empty while the flag is
     * off. Throws when the table cannot be read, so a caller can show nothing rather than a Spam chat.
     */
    fun threadIds(context: Context): Set<Long> =
        if (!ProviderTruthFlag.enabled) {
            emptySet()
        } else {
            context.conversationsDB.getValidSpamThreads().mapTo(HashSet()) { it.threadId }
        }

    /** [items] without those whose chat is in [spam]. */
    fun <T> without(items: List<T>, spam: Set<Long>, threadOf: (T) -> Long): List<T> =
        if (spam.isEmpty()) items else items.filterNot { threadOf(it) in spam }

    /**
     * H-16: the app lock covers Spam and is locked right now, so nothing of a Spam chat may show (a search's Spam
     * section, the rule preview's Spam senders). Always false while ProviderTruthFlag is off.
     */
    fun lockedNow(context: Context): Boolean =
        ProviderTruthFlag.enabled && runCatching {
            LockScope.blockedLocked(context) && context.appLockManager.isLocked()
        }.getOrDefault(true)
}

/**
 * H-16: runs [onReturn] each time the app comes back to the front (the moment the app lock locks again after its
 * timeout), so a screen model can drop what the lock now covers. [start] and [stop] post to the main thread.
 */
class AppReturnWatch(private val onReturn: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) onReturn() }

    fun start() {
        main.post { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
    }

    fun stop() {
        main.post { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }
}
