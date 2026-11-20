package com.glooboo.umessage.spam.mms

import android.net.Uri
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamSafetyCheck

/**
 * Provider truth, 6.2 (step E): a notice the push gate called Spam. [sender] is the push's sender, [cause] why its chat
 * is in Spam, and [downloads] whether it is downloaded now (within the limits and not roaming) or stays a "Tap to
 * download" row. Either way it is shown in the sender's Spam chat at once (E-4). [ruleId]: the block rule, if any.
 */
data class SpamNotice(val sender: String, val cause: SpamCause, val downloads: Boolean, val ruleId: Long? = null)

/** Where a downloaded MMS ends up under provider truth ([IncomingMms.placeDownloaded]). */
sealed interface MmsPlace {
    /** Messages, unread and announced as today; [reason] picks the notification mode (null: a plain notification). */
    data class Messages(val reason: DecisionReason?) : MmsPlace

    /** R4: a group MMS from a blocked member stays in the group in Messages, unread, but without a notification. */
    data object QuietGroup : MmsPlace

    /** Spam: read and silent in the store, no notification, no unarchive, no forward. */
    data class Spam(val cause: SpamCause) : MmsPlace
}

/** A notice row (notification-ind) of the phone's store: its chat (see [IncomingMms.hasChat]) and its sender. */
data class NoticeRow(val threadId: Long, val sender: String)

/**
 * The phone's store and the Spam table, as the incoming MMS path needs them. The app's one is [AndroidMmsSpamStore];
 * tests use a fake. Calls may throw; [IncomingMms] fails open on every error.
 */
interface MmsSpamStore {
    /** The one-to-one chat of [address] in the phone's store, made when missing. */
    fun threadOf(address: String): Long

    /** S9, R12: the Spam row of [threadId] first, then its unread count + 1. */
    fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long)

    /** R1's safety check: [threadId] has a Spam row for [address]. */
    fun isSpamThreadOf(threadId: Long, address: String): Boolean

    /**
     * Marks the MMS row [row] read and seen when [silent], else unread and not seen, and puts it in [threadId] (null:
     * its chat stays), in one update.
     */
    fun setThread(row: Uri, threadId: Long?, silent: Boolean): Boolean

    /** The notice row [row], or null when it is gone. */
    fun noticeOf(row: Uri): NoticeRow?

    /** Whether the store has any SMS or MMS in [threadId]; null when that could not be read. */
    fun hasMessages(threadId: Long): Boolean?

    /** Whether the app holds a scheduled message of [threadId] (it is not in the phone's store). */
    fun hasScheduled(threadId: Long): Boolean

    /** Drops the app's own cached row and Spam row of the empty chat [threadId]. */
    fun forgetEmptyChat(threadId: Long)

    /** E-6: caches the chat [threadId] and tells the open lists, as a new message does. */
    fun showChat(threadId: Long)

    /** Counts one more message decided by the rule [ruleId] (if any) and, when [inSpam], one more Spam message. */
    fun recordHit(ruleId: Long?, inSpam: Boolean)
}

/** The sender checks of one downloaded MMS; the app's one is in MmsReceiver, tests use a fake. */
interface MmsSenderCheck {
    /** The sender's verdict without writing or logging anything; null when the filter failed. */
    fun probe(): FilterDecision?

    /** The text checks with facts first and the verdict (one-to-one); writes its own decision-log row. */
    fun judge(): Judgement?

    /** R2: the sender's history was checked on the spot; false when that failed. */
    fun historyChecked(): Boolean

    /** E-13: the one decision-log row of an MMS placed without [judge]. */
    fun log(decision: String, reason: String, ruleId: Long?)
}

/**
 * Provider truth, 6.2 (R4, answers 8 and 11): every MMS is kept in the phone's store; this decides where it is shown.
 * The library always stores a downloaded MMS (its content hook answers "not blocked"), then:
 *
 * - a group MMS (more than one other participant, or participants that cannot be read) stays in the group, in
 *   Messages: a blocked member's MMS arrives without a notification, an unknown member's with one; no text check, no
 *   fact, no group join;
 * - a one-to-one MMS is judged like an SMS: the history check of its sender, the text checks with facts first, the
 *   sender's verdict, and the safety check after it was marked read and silent.
 *
 * Any error puts the MMS in Messages, unread, with a notification (fail open); a sender with a block rule stays in
 * Spam even when the history check failed (E-11).
 */
class IncomingMms(
    private val store: MmsSpamStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Gives the notice [row] of [sender] its chat, so a "Tap to download" (or "Downloading") row shows. [cause] set:
     * the sender's chat is in Spam, so its Spam row is written first and the notice is read and seen; if that row
     * cannot be written or is not there afterwards (R1), the notice shows unread (E-3). The chat is cached and the
     * lists are told (E-6). True when it is shown in Spam.
     */
    fun revealNotice(row: Uri, sender: String, cause: SpamCause?): Boolean {
        if (sender.isBlank()) return false
        val threadId = store.threadOf(sender)
        val inSpam = cause != null && runCatching {
            store.noteSpamArrival(threadId, sender, cause, now())
            store.isSpamThreadOf(threadId, sender)
        }.getOrDefault(false)
        store.setThread(row, threadId, silent = inSpam)
        runCatching { store.showChat(threadId) }
        return inSpam
    }

    /** A Spam notice that is not downloaded has its final place at once: it counts like a Spam MMS (rule, event). */
    fun countSpamNotice(ruleId: Long?) = recordHits(ruleId, MmsPlace.Spam(SpamCause.UNKNOWN))

    /**
     * Places the downloaded MMS [row] of [sender], stored by the library in [threadId]. [otherParticipants]: the
     * chat's participants without the user's own numbers; null when they could not be read (E-10: then a group).
     */
    fun placeDownloaded(
        row: Uri,
        threadId: Long,
        sender: String,
        otherParticipants: Int?,
        check: MmsSenderCheck,
    ): MmsPlace {
        if (otherParticipants != 1) return placeGroup(check)
        if (!runCatching(check::historyChecked).getOrDefault(false)) return placeUnchecked(row, threadId, sender, check)
        val judgement = runCatching(check::judge).getOrNull() ?: return MmsPlace.Messages(null)
        val verdict = judgement.verdict
        val place = if (verdict is SenderVerdict.Spam) {
            toSpam(row, threadId, sender, verdict.cause)
        } else {
            MmsPlace.Messages(judgement.decision().reason)
        }
        val blockRule = (judgement.decision() as? FilterDecision.Block)?.ruleId?.takeIf { place is MmsPlace.Spam }
        recordHits(judgement.wordRuleId ?: blockRule, place)
        return place
    }

    /**
     * R4: the chat the notice was shown in ([noticeThread]) when the MMS itself landed in another one (a group). The
     * library deleted the notice; when the chat is now empty (no message in the store, no scheduled one in the app,
     * E-12), the app's own rows of it go too.
     */
    fun forgetNoticeChat(noticeThread: Long?, landedIn: Long) {
        if (!hasChat(noticeThread) || noticeThread == landedIn) return
        val thread = noticeThread ?: return
        runCatching {
            if (store.hasMessages(thread) == false && !store.hasScheduled(thread)) store.forgetEmptyChat(thread)
        }
    }

    private fun placeGroup(check: MmsSenderCheck): MmsPlace {
        val decision = runCatching(check::probe).getOrNull()
        val blockedMember = decision is FilterDecision.Block && causeOf(decision) == SpamCause.BLOCKED
        val ruleId = (decision as? FilterDecision.Block)?.ruleId ?: (decision as? FilterDecision.Allow)?.ruleId
        runCatching { check.log(DELIVER, decision?.reason?.name ?: FILTER_FAILED, ruleId) }
        return if (blockedMember) MmsPlace.QuietGroup else MmsPlace.Messages(decision?.reason)
    }

    /** E-11: the history check failed. A block rule still holds (history never allows); anything else fails open. */
    private fun placeUnchecked(row: Uri, threadId: Long, sender: String, check: MmsSenderCheck): MmsPlace {
        val block = runCatching(check::probe).getOrNull() as? FilterDecision.Block
        if (block == null || causeOf(block) != SpamCause.BLOCKED) {
            runCatching { check.log(ERROR, HISTORY_FAILED, null) }
            return MmsPlace.Messages(null)
        }
        val place = toSpam(row, threadId, sender, SpamCause.BLOCKED)
        runCatching { check.log(if (place is MmsPlace.Spam) BLOCK else ERROR, block.reason.name, block.ruleId) }
        recordHits(block.ruleId?.takeIf { place is MmsPlace.Spam }, place)
        return place
    }

    /** Spam row first, then read and silent, then the safety check (R1); any failure: Messages, unread. */
    private fun toSpam(row: Uri, threadId: Long, sender: String, cause: SpamCause): MmsPlace {
        val silent = runCatching {
            store.noteSpamArrival(threadId, sender, cause, now())
            store.setThread(row, null, silent = true)
        }.getOrDefault(false)
        val inSpam = silent && runCatching { store.isSpamThreadOf(threadId, sender) }.getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = silent, threadInSpam = inSpam) || !silent) {
            runCatching { store.setThread(row, null, silent = false) }
            return MmsPlace.Messages(null)
        }
        return MmsPlace.Spam(cause)
    }

    /** A-11: the word rule that made a fact, or the rule that put the sender in Spam, counts one more message. */
    private fun recordHits(ruleId: Long?, place: MmsPlace) {
        if (ruleId != null || place is MmsPlace.Spam) runCatching { store.recordHit(ruleId, place is MmsPlace.Spam) }
    }

    companion object {
        /** E-1: the thread id mmslib's PduPersister gives a notice saved without a chat (DUMMY_THREAD_ID). */
        const val NO_CHAT_THREAD = Long.MAX_VALUE

        const val DELIVER = "DELIVER"
        const val BLOCK = "BLOCK"
        const val ERROR = "ERROR"
        private const val HISTORY_FAILED = "HISTORY_CHECK_FAILED"
        private const val FILTER_FAILED = "FILTER_FAILED"

        /** E-1: [threadId] is a real chat: not unset, not 0, not mmslib's dummy. */
        fun hasChat(threadId: Long?): Boolean = threadId != null && threadId > 0 && threadId != NO_CHAT_THREAD

        /** The cause behind a Block of the push gate or the filter: a rule (the user's or a word's), else unknown. */
        fun causeOf(block: FilterDecision.Block): SpamCause =
            if (block.reason == DecisionReason.BLOCK_RULE) SpamCause.BLOCKED else SpamCause.UNKNOWN

        /**
         * The participants of a chat ([numbers]) that are not one of the user's own numbers ([own], compared with
         * [same]). Null when no participant could be read: the caller then cannot tell a group from a one-to-one chat.
         */
        fun otherParticipants(numbers: List<String>, own: List<String>, same: (String, String) -> Boolean): Int? {
            val read = numbers.filter { it.isNotBlank() }
            if (read.isEmpty()) return null
            return read.count { number -> own.none { same(it, number) } }
        }
    }
}
