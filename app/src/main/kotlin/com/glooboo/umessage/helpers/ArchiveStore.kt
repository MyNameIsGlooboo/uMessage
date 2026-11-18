package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony.Threads
import androidx.core.net.toUri
import com.glooboo.umessage.extensions.config

/**
 * Some phones (seen on a Samsung one) accept a write of the thread archive flag but do not keep it. The app then keeps
 * the archive in its own list: once the phone's store is seen dropping a write, [isTrusted] turns false for good and
 * the list refresh no longer copies the store's archived flag over the app's own.
 */
object ArchiveStore {

    /** True while the phone's store has the archive flag and has kept every write seen so far. */
    fun isTrusted(context: Context): Boolean =
        context.config.isArchiveAvailable && !context.config.isArchiveStoreUnreliable

    /**
     * Checks that the write of [archived] to [threadId], which updated [rows] rows, stuck: the flag is read back. An
     * archive must be there with the flag set; an unarchive is only judged when the flag is still set afterwards.
     */
    fun verifyWrite(context: Context, threadId: Long, archived: Boolean, rows: Int) {
        if (!isTrusted(context)) return
        val stored = readFlag(context, threadId)
        val stuck = if (archived) rows > 0 && stored == true else stored != true
        if (!stuck) context.config.isArchiveStoreUnreliable = true
    }

    /**
     * Self-heal for archives made before the store was seen dropping writes: when the app has archived chats but the
     * store has none at all, the store did not keep them, so the app's own list stays as it is.
     */
    fun protectArchives(context: Context, appHasArchived: Boolean) {
        if (!appHasArchived || !isTrusted(context)) return
        val storeHasArchived = runCatching {
            context.contentResolver.query(
                "${Threads.CONTENT_URI}?simple=true".toUri(),
                arrayOf(Threads._ID),
                "${Threads.ARCHIVED} = 1",
                null,
                null,
            )?.use { it.count > 0 }
        }.getOrNull()
        if (storeHasArchived == false) context.config.isArchiveStoreUnreliable = true
    }

    private fun readFlag(context: Context, threadId: Long): Boolean? = runCatching {
        context.contentResolver.query(
            "${Threads.CONTENT_URI}?simple=true".toUri(),
            arrayOf(Threads.ARCHIVED),
            "${Threads._ID} = ?",
            arrayOf(threadId.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
    }.getOrNull()
}
