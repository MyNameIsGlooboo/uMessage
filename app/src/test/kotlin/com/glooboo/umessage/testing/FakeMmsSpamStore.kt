package com.glooboo.umessage.testing

import android.net.Uri
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.NoticeRow

/**
 * [MmsSpamStore] in memory, for the provider-truth MMS tests (step E). Records every call in [calls] in order, so a
 * test can check that the Spam row is written before a row is marked read and silent (S9).
 */
@Suppress("TooManyFunctions") // The fake of the whole port.
class FakeMmsSpamStore : MmsSpamStore {
    val calls = mutableListOf<String>()

    /** Thread of each one-to-one sender, made on first use. */
    val threads = linkedMapOf<String, Long>()

    /** Spam rows: thread -> (sender, cause, unread). */
    val spamRows = linkedMapOf<Long, Triple<String, SpamCause, Int>>()

    /** The chat and read/seen state of each MMS row. */
    val rowThread = linkedMapOf<Uri, Long>()
    val rowSilent = linkedMapOf<Uri, Boolean>()

    /** Notice rows the store knows; a notice without a chat has mmslib's dummy thread, [NO_CHAT] (E-1). */
    val notices = linkedMapOf<Uri, NoticeRow>()

    /** Threads that still have messages; others are empty. A thread in [unreadable] cannot be read. */
    val threadsWithMessages = mutableSetOf<Long>()
    val unreadable = mutableSetOf<Long>()
    val forgotten = mutableListOf<Long>()

    /** Threads with a scheduled message in the app (E-12), and chats cached and announced to the lists (E-6). */
    val scheduled = mutableSetOf<Long>()
    val shown = mutableListOf<Long>()

    /** E-22: the explicit notice marks. */
    val marks = linkedMapOf<Uri, NoticeMark>()

    val ruleHits = mutableListOf<Long>()
    var spamEvents = 0

    var failSpamRow = false

    /** Every call to [threadOf] throws (the store cannot be read). */
    var failThreads = false

    /** The Spam row is written but is not there afterwards (R1's safety check must catch this). */
    var spamRowVanishes = false

    /** E-26: rows that are gone from the store (the library deleted the notice): [setThread] updates nothing. */
    val goneRows = mutableSetOf<Uri>()

    /** Every [setThread] with a chat throws (the store refused the write). */
    var failSetThread = false

    override fun threadOf(address: String): Long {
        calls += "threadOf"
        check(!failThreads) { "store busy" }
        return threads.getOrPut(address) { 100L + threads.size }
    }

    /** Chats the lists do not show in Spam although their row exists (a group chat by the lists' rule, E-15). */
    val notListed = mutableSetOf<Long>()

    override fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long, raise: Boolean) {
        calls += "noteSpamArrival"
        check(!failSpamRow) { "spam table busy" }
        val row = spamRows[threadId]
        if (!raise && row?.first == address) return
        if (!spamRowVanishes) spamRows[threadId] = Triple(address, row?.second ?: cause, (row?.third ?: 0) + 1)
    }

    override fun lowerSpamUnread(threadId: Long) {
        val row = spamRows[threadId] ?: return
        spamRows[threadId] = row.copy(third = (row.third - 1).coerceAtLeast(0))
    }

    override fun isListedInSpam(threadId: Long): Boolean {
        calls += "isListedInSpam"
        return spamRows[threadId] != null && threadId !in notListed
    }

    override fun isSpamThreadOf(threadId: Long, address: String): Boolean {
        calls += "isSpamThreadOf"
        return spamRows[threadId]?.first == address
    }

    /** Recorded as "setSilent(x)" when the chat stays ([threadId] null), else as "setThread(silent=x)". */
    override fun setThread(row: Uri, threadId: Long?, silent: Boolean): Boolean {
        check(threadId == null || !failSetThread) { "store refused the write" }
        if (row in goneRows) {
            calls += "setThread(gone)"
            return false
        }
        rowSilent[row] = silent
        if (threadId == null) {
            calls += "setSilent($silent)"
            return true
        }
        calls += "setThread(silent=$silent)"
        rowThread[row] = threadId
        notices[row]?.let { notices[row] = it.copy(threadId = threadId) }
        return true
    }

    override fun noticeOf(row: Uri): NoticeRow? = notices[row]

    override fun hasMessages(threadId: Long): Boolean? =
        if (threadId in unreadable) null else threadId in threadsWithMessages

    override fun hasScheduled(threadId: Long): Boolean = threadId in scheduled

    override fun showChat(threadId: Long) {
        shown += threadId
    }

    override fun forgetEmptyChat(threadId: Long) {
        forgotten += threadId
        spamRows.remove(threadId)
    }

    override fun recordHit(ruleId: Long?, inSpam: Boolean) {
        ruleId?.let { ruleHits += it }
        if (inSpam) spamEvents++
    }

    override fun markNotice(row: Uri, mark: NoticeMark?) {
        if (mark == null) marks.remove(row) else marks[row] = mark
    }

    override fun markOf(row: Uri): NoticeMark? = marks[row]

    companion object {
        /** mmslib's DUMMY_THREAD_ID: the thread of a notice saved without a chat. */
        const val NO_CHAT = Long.MAX_VALUE
    }
}
