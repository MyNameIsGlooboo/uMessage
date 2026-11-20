package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony.Threads
import androidx.core.net.toUri
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB

/**
 * Some phones (seen on a Samsung one) accept a write of the thread archive flag but do not keep it. The app then keeps
 * the archive in its own list: once the phone's store is seen dropping a write, [isTrusted] turns false for good and
 * the list refresh no longer copies the store's archived flag over the app's own.
 *
 * The store is only ever judged on proof: a row that was read back with the wrong value. A missing row, a failed read
 * or a row of another thread says nothing and never marks the store.
 */
object ArchiveStore {

    /** True while the phone's store has the archive flag and has kept every write seen so far. */
    fun isTrusted(context: Context): Boolean =
        context.config.isArchiveAvailable && !context.config.isArchiveStoreUnreliable

    /**
     * Checks that the write of [archived] to [threadId] stuck: the one row is read back by its id. Only a row that
     * reads the opposite of what was written marks the store unreliable. A chat that only has scheduled messages is not
     * in the store (its thread id is made up by the app), so it is not checked.
     */
    fun verifyWrite(context: Context, threadId: Long, archived: Boolean) {
        // Never throws: a check that cannot be made says nothing, and the caller still has its own list to update.
        runCatching {
            if (!isTrusted(context)) return
            if (context.conversationsDB.getConversationWithThreadId(threadId)?.isScheduled == true) return
            val stored = readFlag(context, threadId) ?: return
            if (stored != archived) context.config.isArchiveStoreUnreliable = true
        }
    }

    /**
     * Self-heal for archives made before the store was seen dropping writes: when the app's own list has archived
     * chats that exist in the store but the store has none archived at all, the store did not keep them, so the app's
     * own list stays as it is. The archived rows are read now, not from an older list snapshot, and a chat that is
     * scheduled only or already gone from the store does not count.
     */
    fun protectArchives(context: Context) {
        if (!isTrusted(context)) return
        // S1: every cached row, so an archived chat that is shown in Spam still counts.
        val appArchived = context.conversationsDB.getAllRows().filter { it.isArchived && !it.isScheduled }
            .map { it.threadId }
        if (appArchived.isEmpty()) return
        val store = readStore(context) ?: return
        val storeHasArchived = store.values.any { it }
        if (!storeHasArchived && appArchived.any { it in store }) context.config.isArchiveStoreUnreliable = true
    }

    /** The archived flag of every thread of the store by thread id; null when it could not be read. */
    private fun readStore(context: Context): Map<Long, Boolean>? = runCatching {
        context.contentResolver.query(
            "${Threads.CONTENT_URI}?simple=true".toUri(),
            arrayOf(Threads._ID, Threads.ARCHIVED),
            null,
            null,
            null,
        )?.use {
            val flags = HashMap<Long, Boolean>()
            while (it.moveToNext()) flags[it.getLong(0)] = it.getInt(1) == 1
            flags
        }
    }.getOrNull()

    /** The flag of [threadId]; null when the row is not there, is not the one asked for, or could not be read. */
    private fun readFlag(context: Context, threadId: Long): Boolean? = runCatching {
        context.contentResolver.query(
            "${Threads.CONTENT_URI}?simple=true".toUri(),
            arrayOf(Threads._ID, Threads.ARCHIVED),
            "${Threads._ID} = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use {
            var flag: Boolean? = null
            // A store that ignores the selection hands back other threads: only the asked-for row counts.
            while (flag == null && it.moveToNext()) {
                if (it.getLong(0) == threadId) flag = it.getInt(1) == 1
            }
            flag
        }
    }.getOrNull()
}
