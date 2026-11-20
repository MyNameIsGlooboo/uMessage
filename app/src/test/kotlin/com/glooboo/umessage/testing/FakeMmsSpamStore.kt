package com.glooboo.umessage.testing

import android.net.Uri
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.glooboo.umessage.spam.mms.NoticeRow

/**
 * [MmsSpamStore] in memory, for the provider-truth MMS tests (step E). Records every call in [calls] in order, so a
 * test can check that the Spam row is written before a row is marked read and silent (S9).
 */
class FakeMmsSpamStore : MmsSpamStore {
    val calls = mutableListOf<String>()

    /** Thread of each one-to-one sender, made on first use. */
    val threads = linkedMapOf<String, Long>()

    /** Spam rows: thread -> (sender, cause, unread). */
    val spamRows = linkedMapOf<Long, Triple<String, SpamCause, Int>>()

    /** The chat and read/seen state of each MMS row. */
    val rowThread = linkedMapOf<Uri, Long>()
    val rowSilent = linkedMapOf<Uri, Boolean>()

    /** Notice rows the store knows. */
    val notices = linkedMapOf<Uri, NoticeRow>()

    /** Threads that still have messages; others are empty. A thread in [unreadable] cannot be read. */
    val threadsWithMessages = mutableSetOf<Long>()
    val unreadable = mutableSetOf<Long>()
    val forgotten = mutableListOf<Long>()

    val ruleHits = mutableListOf<Long>()
    var spamEvents = 0

    var failSpamRow = false

    /** The Spam row is written but is not there afterwards (R1's safety check must catch this). */
    var spamRowVanishes = false

    override fun threadOf(address: String): Long {
        calls += "threadOf"
        return threads.getOrPut(address) { 100L + threads.size }
    }

    override fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long) {
        calls += "noteSpamArrival"
        check(!failSpamRow) { "spam table busy" }
        val unread = spamRows[threadId]?.third ?: 0
        if (!spamRowVanishes) spamRows[threadId] = Triple(address, cause, unread + 1)
    }

    override fun isSpamThreadOf(threadId: Long, address: String): Boolean {
        calls += "isSpamThreadOf"
        return spamRows[threadId]?.first == address
    }

    override fun setThread(row: Uri, threadId: Long, silent: Boolean): Boolean {
        calls += "setThread(silent=$silent)"
        rowThread[row] = threadId
        if (silent) rowSilent[row] = true
        notices[row]?.let { notices[row] = it.copy(threadId = threadId) }
        return true
    }

    override fun setSilent(row: Uri, silent: Boolean): Boolean {
        calls += "setSilent($silent)"
        rowSilent[row] = silent
        return true
    }

    override fun noticeOf(row: Uri): NoticeRow? = notices[row]

    override fun hasMessages(threadId: Long): Boolean? =
        if (threadId in unreadable) null else threadId in threadsWithMessages

    override fun forgetEmptyChat(threadId: Long) {
        forgotten += threadId
        spamRows.remove(threadId)
    }

    override fun recordRuleHit(ruleId: Long) {
        ruleHits += ruleId
    }

    override fun recordSpamEvent() {
        spamEvents++
    }
}
