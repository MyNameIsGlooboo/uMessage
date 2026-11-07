package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.moveMessageToRecycleBin
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import kotlinx.coroutines.runBlocking

/**
 * I02 and I45: moves old read verification codes ([CleanupPrefs.codeDays]) and old messages
 * ([CleanupPrefs.oldMessageDays]) to the recycle bin. It never deletes for good: with the bin off it does nothing.
 * Which messages qualify is decided by [MessageRetention]; this class reads the cache in pages, moves a batch of at
 * most [MessageRetention.BATCH] and tells the lists. Blocking: call it on a background thread.
 */
class RetentionRunner(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.cleanupPrefs

    /** The daily and receive-time check: does one batch, at most once per [CleanupPrefs.CLEANUP_INTERVAL_MS]. */
    fun runIfDue(now: Long) {
        if (prefs.codeDays == 0 && prefs.oldMessageDays == 0) return
        if (!app.config.useRecycleBin || !prefs.claimCleanup(now)) return
        moveBatch(now)
    }

    /** After the user chose an age and confirmed: moves everything due now, batch after batch. Returns the count. */
    fun runNow(now: Long): Int {
        if (!app.config.useRecycleBin) return 0
        var moved = 0
        repeat(MAX_ROUNDS) {
            val count = moveBatch(now)
            moved += count
            if (count < MessageRetention.BATCH) return moved
        }
        return moved
    }

    /** How many messages [days] of keep-time would move to the bin now (counted up to [PREVIEW_LIMIT]). */
    fun countOld(days: Int, now: Long): Int = pickOld(days, now, PREVIEW_LIMIT).size

    private fun moveBatch(now: Long): Int {
        val codeDays = prefs.codeDays
        val oldDays = prefs.oldMessageDays
        val codes = if (codeDays > 0) pickCodes(codeDays, now) else emptyList()
        val old = if (oldDays > 0) pickOld(oldDays, now, MessageRetention.BATCH) else emptyList()
        val batch = (codes + old).distinctBy { it.key }.take(MessageRetention.BATCH)
        batch.forEach { app.moveMessageToRecycleBin(it.id, it.isMMS, it) }
        if (batch.isNotEmpty()) {
            app.updateLastConversationMessage(batch.map { it.threadId }.toSet())
            refreshConversations()
            refreshMessages()
        }
        return batch.size
    }

    private fun isStarred(message: Message) = app.starredDB.isStarred(message.id, message.isMMS)

    /** F6: the messages that carry a private note or a reminder, looked up once per scan. */
    private fun keptLookup(): (Message) -> Boolean {
        val noteKeys = runCatching { runBlocking { app.messageNotesDB.getAllKeys() } }.getOrDefault(emptyList()).toSet()
        val reminded = runCatching { runBlocking { app.getMessagesDB().MessageRemindersDao().getAll() } }
            .getOrDefault(emptyList()).map { it.key }.toSet()
        return { m -> MessageNoteKey.of(m) in noteKeys || MessageKey(m.id, m.isMMS) in reminded }
    }

    private fun pickCodes(days: Int, now: Long): List<Message> {
        val cutoff = MessageRetention.cutoffSeconds(now, days)
        val kept = keptLookup()
        return scan(cutoff, MessageRetention.BATCH) {
            MessageRetention.selectCodes(it, cutoff, kept) { m -> isStarred(m) }
        }
    }

    private fun pickOld(days: Int, now: Long, limit: Int): List<Message> {
        val cutoff = MessageRetention.cutoffSeconds(now, days)
        val pinned = app.config.pinnedConversations.mapNotNull { it.toLongOrNull() }.toSet()
        val kept = keptLookup()
        return scan(cutoff, limit) { MessageRetention.selectOld(it, cutoff, pinned, kept) { m -> isStarred(m) } }
    }

    /**
     * Pages through the old messages and keeps what [pick] chooses until [limit] are found or [MAX_SCANNED] were read.
     * Nothing is moved while reading, so the pages do not shift under the offset.
     */
    private fun scan(cutoff: Int, limit: Int, pick: (List<Message>) -> List<Message>): List<Message> {
        val found = ArrayList<Message>()
        var offset = 0
        while (found.size < limit && offset < MAX_SCANNED) {
            val page = app.messagesDB.getRetentionCandidates(cutoff, PAGE, offset)
            found += pick(page)
            if (page.size < PAGE) break
            offset += PAGE
        }
        return found.take(limit)
    }

    private companion object {
        const val PAGE = 1000
        const val MAX_SCANNED = 20_000
        const val MAX_ROUNDS = 40
        const val PREVIEW_LIMIT = 5000
    }
}
