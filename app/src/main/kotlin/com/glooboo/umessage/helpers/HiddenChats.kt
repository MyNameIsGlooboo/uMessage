package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.fossify.commons.extensions.baseConfig

/**
 * B06: the conversations the user hid behind the app lock; the one place that says which thread ids to keep out of
 * sight. Hiding works only while an app lock is on: without one the chats show as usual (and hiding first sends the
 * user to set one up).
 *
 * Everything that lists conversations or messages asks [isHidden] (or filters with [withoutHidden]): a hidden chat is
 * out of the inbox, search, tiles, stars, media lists, shortcuts and so on until the user unlocks the
 * hidden chats for the session ([HiddenChatsSession]). What must never show, even while unlocked, because someone else
 * can see it (notifications, launcher shortcuts), asks [isMarked] instead.
 *
 * The ids live in their own preferences file, not in the database.
 */
class HiddenChats(
    private val prefs: SharedPreferences,
    private val lockOn: () -> Boolean,
    private val sessionUnlocked: () -> Boolean = HiddenChatsSession::isUnlocked,
) {
    /** Every thread id the user chose to hide, whether the lock is on or not. */
    fun chosenIds(): Set<Long> =
        prefs.getStringSet(KEY_IDS, null).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    /** True while an app lock is on; without one nothing is hidden. */
    fun isLockOn(): Boolean = lockOn()

    /** True when [threadId] was chosen to be hidden (what the thread menu's Hide / Unhide shows). */
    fun isChosen(threadId: Long): Boolean = threadId in chosenIds()

    /** True when [threadId] is hidden whether or not the hidden chats are unlocked right now. */
    fun isMarked(threadId: Long): Boolean = lockOn() && isChosen(threadId)

    /** True when [threadId] must stay out of sight now: hidden, and not unlocked for this session. */
    fun isHidden(threadId: Long): Boolean = isMarked(threadId) && !sessionUnlocked()

    /** The thread ids that must stay out of sight now; empty while the lock is off or the chats are unlocked. */
    fun hiddenIds(): Set<Long> = if (lockOn() && !sessionUnlocked()) chosenIds() else emptySet()

    /** [items] without the ones that belong to a hidden thread. */
    fun <T> withoutHidden(items: List<T>, threadOf: (T) -> Long): List<T> {
        val hidden = hiddenIds()
        return if (hidden.isEmpty()) items else items.filterNot { threadOf(it) in hidden }
    }

    fun hide(threadId: Long) = prefs.edit { putStringSet(KEY_IDS, (chosenIds() + threadId).mapTo(HashSet()) { "$it" }) }

    fun unhide(threadId: Long) =
        prefs.edit { putStringSet(KEY_IDS, (chosenIds() - threadId).mapTo(HashSet()) { "$it" }) }

    companion object {
        private const val PREFS_NAME = "spamless_hidden_chats"
        private const val KEY_IDS = "thread_ids"

        fun get(context: Context): HiddenChats {
            val app = context.applicationContext
            return HiddenChats(
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                lockOn = { app.baseConfig.isAppPasswordProtectionOn },
            )
        }
    }
}
