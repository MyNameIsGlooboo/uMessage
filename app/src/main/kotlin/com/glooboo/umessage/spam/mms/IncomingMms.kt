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
 * download" row in the sender's Spam chat. [ruleId]: the block rule, if any; [reason] and [subscriptionId] are for
 * its decision-log row.
 */
data class SpamNotice(
    val sender: String,
    val cause: SpamCause,
    val downloads: Boolean,
    val ruleId: Long? = null,
    val reason: String = "",
    val subscriptionId: Int = -1,
)

/** Where a downloaded MMS ends up under provider truth ([IncomingMms.placeDownloaded]). */
sealed interface MmsPlace {
    /** Messages, unread and announced as today; [reason] picks the notification mode (null: a plain notification). */
    data class Messages(val reason: DecisionReason?) : MmsPlace

    /** R4: a group MMS from a blocked member stays in the group in Messages, unread, but without a notification. */
    data object QuietGroup : MmsPlace

    /** Spam: read and silent in the store, no notification, no unarchive, no forward. */
    data class Spam(val cause: SpamCause) : MmsPlace
}

/**
 * A notice row (notification-ind) of the phone's store: its chat (see [IncomingMms.hasChat]), its sender, and whether
 * it is read. A read notice in a Spam chat was counted there already (E-8): its Spam row raised the unread count once.
 */
data class NoticeRow(val threadId: Long, val sender: String, val read: Boolean = false)

/**
 * The phone's store and the Spam table, as the incoming MMS path needs them. The app's one is [AndroidMmsSpamStore];
 * tests use a fake. Calls may throw; [IncomingMms] fails open on every error.
 */
@Suppress("TooManyFunctions") // One port for the store, the Spam table and the lists, so tests have one fake.
interface MmsSpamStore {
    /** The one-to-one chat of [address] in the phone's store, made when missing. */
    fun threadOf(address: String): Long

    /**
     * S9, R12: the Spam row of [threadId] first, then its unread count + 1. [raise] false (E-8, the message was counted
     * already): only a missing row is written, with its count.
     */
    fun noteSpamArrival(threadId: Long, address: String, cause: SpamCause, at: Long, raise: Boolean)

    /** E-8: one message counted in [threadId]'s Spam row went elsewhere: its unread count - 1 (never below 0). */
    fun lowerSpamUnread(threadId: Long)

    /** [threadId] has a Spam row for [address] (the row alone; the chat may not be cached yet). */
    fun isSpamThreadOf(threadId: Long, address: String): Boolean

    /** E-15, R1's safety check: the lists show the cached chat [threadId] in Spam (one-to-one, its sender's row). */
    fun isListedInSpam(threadId: Long): Boolean

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
 * - a group MMS (more than one recipient in the chat, by the lists' own rule, E-15; or recipients that cannot be read)
 *   stays in the group, in Messages: a blocked member's MMS arrives without a notification, an unknown member's with
 *   one; no text check, no fact, no group join;
 * - a one-to-one MMS is judged like an SMS: the history check of its sender, the text checks with facts first, the
 *   sender's verdict, and the safety check after it was marked read and silent: the lists must show its chat in Spam.
 *
 * Any error puts the MMS in Messages, unread, with a notification (fail open); a sender with a block rule stays in
 * Spam even when the history check failed (E-11). Each message raises a Spam count and a rule hit once (E-8).
 */
class IncomingMms(
    private val store: MmsSpamStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Gives the notice [row] of [sender] its final chat, so a "Tap to download" row shows. [cause] set: the sender's
     * chat is in Spam, so its Spam row is written first (the notice counts as the message) and the notice is read and
     * seen; if that row cannot be written or is not there afterwards (R1), the notice shows unread (E-3). The chat is
     * cached and the lists are told (E-6). True when it is shown in Spam.
     */
    fun revealNotice(row: Uri, sender: String, cause: SpamCause?): Boolean {
        if (sender.isBlank()) return false
        val threadId = store.threadOf(sender)
        val inSpam = cause != null && runCatching {
            store.noteSpamArrival(threadId, sender, cause, now(), raise = true)
            store.isSpamThreadOf(threadId, sender)
        }.getOrDefault(false)
        store.setThread(row, threadId, silent = inSpam)
        runCatching { store.showChat(threadId) }
        return inSpam
    }

    /**
     * A Spam notice that downloads now gets no Spam row and no count at save (the download may be a group MMS): it is
     * put in the sender's chat, unread, only when that chat already is the sender's Spam chat or is new and empty;
     * otherwise it keeps mmslib's dummy thread until the MMS is placed (a lost result is found by the sweep).
     */
    fun parkNotice(row: Uri, sender: String) {
        if (sender.isBlank()) return
        val threadId = store.threadOf(sender)
        val spamChat = runCatching { store.isSpamThreadOf(threadId, sender) }.getOrDefault(false)
        if (!spamChat && store.hasMessages(threadId) != false) return
        store.setThread(row, threadId, silent = false)
        if (spamChat) runCatching { store.showChat(threadId) }
    }

    /** A Spam notice that is not downloaded has its final place at once: it counts like a Spam MMS (rule, event). */
    fun countSpamNotice(ruleId: Long?) = recordHits(ruleId, MmsPlace.Spam(SpamCause.UNKNOWN))

    /**
     * Places the downloaded MMS [row] of [sender], stored by the library in [threadId]. [recipients]: the recipients
     * of that chat as the lists count them; null when one of them could not be read (E-10: then a group).
     * [noticeCounted]: the notice of this MMS was counted in this very Spam chat already (E-8).
     */
    fun placeDownloaded(
        row: Uri,
        threadId: Long,
        sender: String,
        recipients: Int?,
        check: MmsSenderCheck,
        noticeCounted: Boolean = false,
    ): MmsPlace {
        if (recipients != 1) return placeGroup(check)
        if (!runCatching(check::historyChecked).getOrDefault(false)) {
            return placeUnchecked(row, threadId, sender, check, noticeCounted)
        }
        val judgement = runCatching(check::judge).getOrNull() ?: return MmsPlace.Messages(null)
        val verdict = judgement.verdict
        val place = if (verdict is SenderVerdict.Spam) {
            toSpam(row, threadId, sender, verdict.cause, noticeCounted)
        } else {
            MmsPlace.Messages(judgement.decision().reason)
        }
        val blockRule = (judgement.decision() as? FilterDecision.Block)?.ruleId?.takeIf { place is MmsPlace.Spam }
        if (!noticeCounted) recordHits(judgement.wordRuleId ?: blockRule, place)
        return place
    }

    /**
     * R4: the chat the notice was shown in ([noticeThread]) when the MMS itself landed in another one (a group). The
     * library deleted the notice; when the chat is now empty (no message in the store, no scheduled one in the app,
     * E-12), the app's own rows of it go too. A chat that stays loses the count the notice raised ([noticeCounted]).
     */
    fun forgetNoticeChat(noticeThread: Long?, landedIn: Long, noticeCounted: Boolean = false) {
        if (!hasChat(noticeThread) || noticeThread == landedIn) return
        val thread = noticeThread ?: return
        runCatching {
            if (store.hasMessages(thread) == false && !store.hasScheduled(thread)) {
                store.forgetEmptyChat(thread)
            } else if (noticeCounted) {
                store.lowerSpamUnread(thread)
            }
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
    private fun placeUnchecked(
        row: Uri,
        threadId: Long,
        sender: String,
        check: MmsSenderCheck,
        counted: Boolean,
    ): MmsPlace {
        val block = runCatching(check::probe).getOrNull() as? FilterDecision.Block
        if (block == null || causeOf(block) != SpamCause.BLOCKED) {
            runCatching { check.log(ERROR, HISTORY_FAILED, null) }
            return MmsPlace.Messages(null)
        }
        val place = toSpam(row, threadId, sender, SpamCause.BLOCKED, counted)
        runCatching { check.log(if (place is MmsPlace.Spam) BLOCK else ERROR, block.reason.name, block.ruleId) }
        if (!counted) recordHits(block.ruleId?.takeIf { place is MmsPlace.Spam }, place)
        return place
    }

    /**
     * Spam row first, then the chat cached, then read and silent, then the safety check by the lists' own rule (R1,
     * E-15): the chat must be listed in Spam. Any failure: Messages, unread, announced.
     */
    private fun toSpam(row: Uri, threadId: Long, sender: String, cause: SpamCause, counted: Boolean): MmsPlace {
        val silent = runCatching {
            store.noteSpamArrival(threadId, sender, cause, now(), raise = !counted)
            runCatching { store.showChat(threadId) }
            store.setThread(row, null, silent = true)
        }.getOrDefault(false)
        val listed = silent && runCatching { store.isListedInSpam(threadId) }.getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = silent, threadInSpam = listed) || !silent) {
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
         * E-15: the recipients of a chat ([numbers]) counted as the lists count them (every recipient, the user's own
         * number too, as long as the cache does). Null when one of them could not be read (E-10): the caller then
         * cannot tell a group from a one-to-one chat.
         */
        fun recipients(numbers: List<String>): Int? =
            numbers.takeIf { it.isNotEmpty() && it.none(String::isBlank) }?.size
    }
}
