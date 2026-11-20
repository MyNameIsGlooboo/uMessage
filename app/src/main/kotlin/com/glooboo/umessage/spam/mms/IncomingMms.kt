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
 * download" row in the sender's Spam chat.
 */
data class SpamNotice(val sender: String, val cause: SpamCause, val downloads: Boolean)

/** Where a downloaded MMS ends up under provider truth ([IncomingMms.placeDownloaded]). */
sealed interface MmsPlace {
    /** Messages, unread and announced as today; [reason] picks the notification mode (null: a plain notification). */
    data class Messages(val reason: DecisionReason?) : MmsPlace

    /** R4: a group MMS from a blocked member stays in the group in Messages, unread, but without a notification. */
    data object QuietGroup : MmsPlace

    /** Spam: read and silent in the store, no notification, no unarchive, no forward. */
    data class Spam(val cause: SpamCause) : MmsPlace
}

/** A notice row (notification-ind) of the phone's store: its chat (0 when it has none yet) and its sender. */
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

    /** Puts the MMS row [row] in [threadId]; [silent] also marks it read and seen, in the same update. */
    fun setThread(row: Uri, threadId: Long, silent: Boolean): Boolean

    /** Marks the MMS row [row] read and seen ([silent]) or unread and not seen. */
    fun setSilent(row: Uri, silent: Boolean): Boolean

    /** The notice row [row], or null when it is gone. */
    fun noticeOf(row: Uri): NoticeRow?

    /** Whether the store has any SMS or MMS in [threadId]; null when that could not be read. */
    fun hasMessages(threadId: Long): Boolean?

    /** Drops the app's own cached row and Spam row of the empty chat [threadId]. */
    fun forgetEmptyChat(threadId: Long)

    /** Counts one more message decided by the rule [ruleId]. */
    fun recordRuleHit(ruleId: Long)

    /** One more message went to Spam (the Spam statistics). */
    fun recordSpamEvent()
}

/**
 * Provider truth, 6.2 (R4, answers 8 and 11): every MMS is kept in the phone's store; this decides where it is shown.
 * The library always stores a downloaded MMS (its content hook answers "not blocked"), then:
 *
 * - a group MMS (more than one other participant) stays in the group, in Messages: a blocked member's MMS arrives
 *   without a notification, an unknown member's with one; no text check, no fact, no group join;
 * - a one-to-one MMS is judged like an SMS: the history check of its sender, the text checks with facts first, the
 *   sender's verdict, and the safety check after it was marked read and silent.
 *
 * Any error puts the MMS in Messages, unread, with a notification (fail open).
 */
class IncomingMms(
    private val store: MmsSpamStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Gives the notice [row] of [sender] its chat, so a "Tap to download" row shows. [cause] set: the sender's chat is
     * in Spam, so its Spam row is written first and the notice is read and seen; if that row cannot be written or is
     * not there afterwards (R1), the notice shows unread. True when it is shown in Spam.
     */
    fun revealNotice(row: Uri, sender: String, cause: SpamCause?): Boolean {
        if (sender.isBlank()) return false
        val threadId = store.threadOf(sender)
        val inSpam = cause != null && runCatching {
            store.noteSpamArrival(threadId, sender, cause, now())
            store.isSpamThreadOf(threadId, sender)
        }.getOrDefault(false)
        store.setThread(row, threadId, silent = inSpam)
        return inSpam
    }

    /** Marks a Spam notice that downloads at once read and seen; it has no chat until the download ends. */
    fun quietNotice(row: Uri) {
        runCatching { store.setSilent(row, silent = true) }
    }

    /**
     * Places the downloaded MMS [row] of [sender], stored by the library in [threadId]. [probe]: the sender's verdict
     * without writing anything (groups). [judge]: the text checks with facts first and the verdict (one-to-one).
     * [historyChecked]: R2, the sender's history was checked on the spot (false when that failed).
     */
    fun placeDownloaded(
        row: Uri,
        threadId: Long,
        sender: String,
        isGroup: Boolean,
        probe: () -> FilterDecision?,
        judge: () -> Judgement?,
        historyChecked: () -> Boolean,
    ): MmsPlace {
        if (isGroup) return placeGroup(probe)
        if (!runCatching(historyChecked).getOrDefault(false)) return MmsPlace.Messages(null)
        val judgement = runCatching(judge).getOrNull() ?: return MmsPlace.Messages(null)
        val verdict = judgement.verdict
        val place = if (verdict is SenderVerdict.Spam) {
            toSpam(row, threadId, sender, verdict.cause)
        } else {
            MmsPlace.Messages(judgement.decision().reason)
        }
        recordHits(judgement, place)
        return place
    }

    /**
     * R4: the chat the notice was shown in ([noticeThread]) when the MMS itself landed in another one (a group). The
     * library deleted the notice; when the chat is now empty, the app's own rows of it go too.
     */
    fun forgetNoticeChat(noticeThread: Long?, landedIn: Long) {
        if (noticeThread == null || noticeThread <= 0 || noticeThread == landedIn) return
        runCatching { if (store.hasMessages(noticeThread) == false) store.forgetEmptyChat(noticeThread) }
    }

    private fun placeGroup(probe: () -> FilterDecision?): MmsPlace {
        val decision = runCatching(probe).getOrNull()
        val blockedMember = decision is FilterDecision.Block && causeOf(decision) == SpamCause.BLOCKED
        return if (blockedMember) MmsPlace.QuietGroup else MmsPlace.Messages(decision?.reason)
    }

    /** Spam row first, then read and silent, then the safety check (R1); any failure: Messages, unread. */
    private fun toSpam(row: Uri, threadId: Long, sender: String, cause: SpamCause): MmsPlace {
        val silent = runCatching {
            store.noteSpamArrival(threadId, sender, cause, now())
            store.setSilent(row, silent = true)
        }.getOrDefault(false)
        val inSpam = silent && runCatching { store.isSpamThreadOf(threadId, sender) }.getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = silent, threadInSpam = inSpam) || !silent) {
            runCatching { store.setSilent(row, silent = false) }
            return MmsPlace.Messages(null)
        }
        return MmsPlace.Spam(cause)
    }

    /** A-11: the word rule that made a fact, or the rule that put the sender in Spam, counts one more message. */
    private fun recordHits(judgement: Judgement, place: MmsPlace) {
        val decision = judgement.decision()
        val ruleId = judgement.wordRuleId
            ?: (decision as? FilterDecision.Block)?.takeIf { place is MmsPlace.Spam }?.ruleId
        runCatching {
            ruleId?.let(store::recordRuleHit)
            if (place is MmsPlace.Spam) store.recordSpamEvent()
        }
    }

    companion object {
        /** The cause behind a Block of the push gate or the filter: a rule (the user's or a word's), else unknown. */
        fun causeOf(block: FilterDecision.Block): SpamCause =
            if (block.reason == DecisionReason.BLOCK_RULE) SpamCause.BLOCKED else SpamCause.UNKNOWN
    }
}
