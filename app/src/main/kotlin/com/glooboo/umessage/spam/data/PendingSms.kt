package com.glooboo.umessage.spam.data

import android.content.Context
import android.provider.Telephony
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.annotation.VisibleForTesting
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.receivers.PendingSmsRetry
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.HistoryCheck
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy
import com.glooboo.umessage.spam.mms.AppUnknownNotice
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamChanges
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderFact
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.SpamSafetyCheck
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** An SMS that reached the receiver but has not been filtered and saved yet. Written first, deleted last. */
@Entity(tableName = "pending_sms")
data class PendingSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val subject: String,
    val body: String,
    val date: Long,
    val subscriptionId: Int,
    val status: Int,
    val createdAt: Long,
    /** The carrier's send time from the PDU in milliseconds, 0 when unknown. [date] stays the arrival time. */
    @ColumnInfo(defaultValue = "0") val dateSent: Long = 0L,
    /**
     * Added in version 8. True for a message the user restored from Blocked (spam-2): it already passed the filter by
     * the user's decision, so a replay delivers it to the inbox without filtering it again.
     */
    @ColumnInfo(defaultValue = "0") val approved: Boolean = false,
)

object PendingSmsPolicy {
    /** A receiver normally finishes within seconds; older rows belong to a process that died. */
    const val GRACE_MS = 30_000L

    /**
     * After this long rows stop going through the filter (it may be what keeps failing) and are delivered
     * straight to the inbox instead. They are never dropped without being delivered.
     */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** A row being processed in this process is left alone by replay for this long. */
    const val LEASE_MS = 5L * 60 * 1000

    fun isDue(createdAt: Long, now: Long): Boolean = now - createdAt >= GRACE_MS

    fun isExpired(createdAt: Long, now: Long): Boolean = now - createdAt > MAX_AGE_MS

    /** [leasedAt] is when this process started working on the row, null when nobody is. */
    fun isLeased(leasedAt: Long?, now: Long): Boolean = leasedAt != null && now >= leasedAt && now - leasedAt < LEASE_MS
}

/** The single path every incoming SMS takes: filter, then store as blocked or deliver to the inbox. */
object IncomingSmsProcessor {

    /**
     * Filters and stores [message]. Throws when it could not be saved anywhere, so the caller keeps the pending
     * row (it is then replayed later). A filter failure never throws: the message is delivered instead.
     */
    fun process(context: Context, message: PendingSmsEntity, replayed: Boolean = false) {
        ProviderTruthSms.process(context, message, replayed)
    }

    /**
     * J18: the inbox write of [message]. A replayed text may name a SIM that is gone by now, so its write goes through
     * [deliverReadably]; the filter and the forward rules still see the SIM it arrived on. A live text is written as it
     * is: its SIM is the receiving one.
     */
    internal fun deliverFor(context: Context, message: PendingSmsEntity, notify: NotifyContext, replayed: Boolean) =
        if (replayed) deliverReadably(context, message, notify) else deliver(context, message, notify)

    /**
     * Hands an SMS that is in the inbox (in Messages) to the forward rules. Never throws, so a forwarding problem
     * cannot make the caller keep (and later replay) a message that is already delivered.
     */
    internal fun forward(context: Context, message: PendingSmsEntity) {
        ForwardDelivery.onInboxMessage(
            context,
            ForwardCandidate(
                address = message.address,
                body = message.body,
                subscriptionId = message.subscriptionId,
                isMms = false,
                receivedAt = message.date,
            ),
        )
    }

    /**
     * I03: how loud the notification of an inbox message is. In Off mode a number the user has written to counts as
     * known, as it does in the other modes. A failed filter ([decision] null) notifies as usual.
     */
    internal fun notifyContextFor(
        context: Context,
        message: PendingSmsEntity,
        decision: FilterDecision?,
    ): NotifyContext {
        // Provider truth: the hidden "wrote to" check is gone; such a sender is a member of "People you wrote to".
        val reason = (decision as? FilterDecision.Allow)?.reason
        val setting = runCatching { SpamEngine.repository(context).currentSettings().unknownInboxNotify }
            .getOrDefault(SpamSettings().unknownInboxNotify)
        val hasCode = OtpCodeExtractor.extract(message.body) != null
        return NotifyContext(UnknownNotifyPolicy.modeFor(reason, setting, hasCode), reason)
    }

    /**
     * J18: [deliver] for a message whose SIM may be gone (a restored or old held one): the SIM is dropped when it is
     * not active, and a row the store hides is deleted and written again without it ([MissingSim]). Returns the id of
     * the row that is finally there; throws when none could be made visible.
     */
    fun deliverReadably(context: Context, message: PendingSmsEntity, notify: NotifyContext): Long =
        MissingSim.writeReadably(context, message.subscriptionId, isMms = false) { sim ->
            deliver(context, message.copy(subscriptionId = sim), notify)
        }

    /**
     * Puts [message] in the inbox. [deliverIncomingSms] throws only when the provider insert itself did not happen,
     * so one plain retry here cannot create a duplicate (and is skipped when the row is somehow already there).
     * Throws [SmsInsertException] when the message is still not stored.
     */
    fun deliver(context: Context, message: PendingSmsEntity, notify: NotifyContext = NotifyContext()): Long {
        return try {
            context.deliverIncomingSms(
                address = message.address,
                subject = message.subject,
                body = message.body,
                date = message.date,
                subscriptionId = message.subscriptionId,
                status = message.status,
                notify = notify,
                dateSent = message.dateSent,
            )
        } catch (first: Throwable) {
            val id = insertRaw(context, message, first)
            // Stored without the notification step: leave a marker, the replay that follows the receive posts it.
            if (id > 0L && notify.mode != NotifyMode.NONE) {
                NotifyDue.mark(context, id, message.date, notify, live = false)
                // R2: and the retry alarm, should this process die before that replay. A booked one is kept.
                runCatching { PendingSmsRetry.scheduleUnlessBooked(context) }
            }
            id
        }
    }

    /**
     * Last resort: a bare provider insert without the cache, conversation or notification steps. [read] 1 with [seen] 1
     * is a text for Spam (provider truth).
     */
    internal fun insertRaw(
        context: Context,
        message: PendingSmsEntity,
        cause: Throwable? = null,
        read: Int = 0,
        seen: Int? = null,
    ): Long {
        if (PendingSmsQueue.alreadyInInbox(context, message)) return -1L
        val threadId = runCatching { context.getThreadId(message.address) }.getOrDefault(0L)
        return try {
            context.insertSmsOrThrow(
                address = message.address,
                subject = message.subject,
                body = message.body,
                date = message.date,
                read = read,
                threadId = threadId,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                subscriptionId = message.subscriptionId,
                dateSent = message.dateSent,
                seen = seen,
            )
        } catch (t: Throwable) {
            throw SmsInsertException("SMS could not be stored", cause ?: t)
        }
    }
}

/**
 * Provider truth (6.1): the incoming SMS path. The sender's history is known first (R2,
 * B2-14), then the text's fact is written (R1, [SpamEngine.judge]), then the one verdict of the sender says where the
 * text goes:
 *
 * - Messages: as before, unread, with the notification and its "notify due" marker, then the forward rules.
 * - Spam: the chat's spam_threads row first, then the text read and seen, with no notification, no unarchive and
 *   never any forwarding (also when it ends in Messages after all); once it really stays in Spam, unread + 1, a
 *   "blocked" event and the rule's hit. Cause UNKNOWN with the opt-in option on: the quiet notice of that chat
 *   ([BlockedNotifier.show]). A blocked sender never notifies.
 *
 * The place is decided by the rule the lists use ([SpamPlacement]). The safety check after the insert
 * ([SpamSafetyCheck]): a text stored read and silent whose chat is not in Spam is made unread and announced. Any error
 * on the way is fail open: Messages, unread, announced.
 */
internal object ProviderTruthSms {

    /** How a text for Spam ended. */
    private enum class SpamOutcome { STORED, DUPLICATE, ANNOUNCED, NOT_STORED }

    /** One inbox row of the phone's store. */
    private class StoredRow(val id: Long, val read: Int)

    /** Stores [message] where its sender's chat is. Throws only when it could not be stored (or announced) at all. */
    fun process(context: Context, message: PendingSmsEntity, replayed: Boolean = false) {
        val threadId = runCatching { context.getThreadId(message.address) }.getOrDefault(0L)
        val known = runCatching { ArrivalFacts.historyKnown(context, message.address, threadId) }.getOrDefault(false)
        if (!known) logIssue(context, message, "HISTORY_CHECK_FAILED")
        val judgement = if (known) SpamEngine.judge(context, message.toIncoming()) else null
        val placed = judgement?.let { ArrivalFacts.wroteToOnArrival(context, message.address, it) }
        // E-4: the sent history could not be read; fail open, as the history check does.
        if (judgement != null && placed == null) logIssue(context, message, "HISTORY_CHECK_FAILED")
        val spam = placed?.verdict as? SenderVerdict.Spam
        if (placed != null && spam != null) {
            // Reviewer: a text judged Spam is never forwarded, wherever it ends.
            val outcome = storeInSpam(context, message, threadId, placed, spam.cause, replayed)
            if (outcome == SpamOutcome.NOT_STORED) {
                SilentMarks.clear(context, message)
                val notify = IncomingSmsProcessor.notifyContextFor(context, message, null)
                IncomingSmsProcessor.deliverFor(context, message, notify, replayed)
            }
            return
        }
        // E-2: a mark left by an earlier attempt must not make this Messages text look silent to a later replay.
        SilentMarks.clear(context, message)
        val decision = ArrivalFacts.messagesDecision(context, message.address, placed)
        val notify = IncomingSmsProcessor.notifyContextFor(context, message, decision)
        IncomingSmsProcessor.deliverFor(context, message, notify, replayed)
        // A-11, D-8: the allowed word that let the text in counts it (a blocked word never does here).
        (placed?.fact as? SenderFact.AutoAllow)?.let { countSpam(context, it.wordRuleId, event = false) }
        IncomingSmsProcessor.forward(context, message)
    }

    /**
     * Reviewer: a queued text older than seven days skips the filter's facts, not its sender's place. A sender judged
     * Spam (blocked or unknown) gets the text silently in Spam; anything else, or a failure, is delivered as before.
     */
    fun deliverExpired(context: Context, row: PendingSmsEntity) {
        val threadId = runCatching { context.getThreadId(row.address) }.getOrDefault(0L)
        val first = verdictOf(context, row.address) as? SenderVerdict.Spam
        // E-3: only a blocked sender's text goes silently to Spam at once. An unknown sender's history is checked
        // first (R2, B2-14, D-4); a failed check is fail open.
        val spam = when {
            first == null || first.cause == SpamCause.BLOCKED -> first
            !runCatching { ArrivalFacts.historyKnown(context, row.address, threadId) }.getOrDefault(false) -> null
            else -> verdictOf(context, row.address)
                ?.let { ArrivalFacts.wroteToOnArrival(context, row.address, Judgement(it)) }
                ?.verdict as? SenderVerdict.Spam
        }
        val outcome = spam?.let { storeInSpam(context, row, threadId, Judgement(it), it.cause, replayed = true) }
        if (outcome != null && outcome != SpamOutcome.NOT_STORED) return
        SilentMarks.clear(context, row)
        IncomingSmsProcessor.deliverReadably(context, row, NotifyContext())
    }

    /** The sender's place without writing anything; null when the filter failed. */
    private fun verdictOf(context: Context, address: String): SenderVerdict? =
        runCatching { (UMessage.filter(context) as DefaultSpamFilter).senderJudge.verdict(address) }.getOrNull()

    /** 6.1, the Spam verdict: the row first, then the text read and silent, then the safety check. */
    private fun storeInSpam(
        context: Context,
        message: PendingSmsEntity,
        threadId: Long,
        judgement: Judgement,
        cause: SpamCause,
        replayed: Boolean,
    ): SpamOutcome {
        if (threadId <= 0L) return SpamOutcome.NOT_STORED
        // As the held-message store did, for Spam only: the same text from the same sender within ten seconds is one.
        val earlier = inboxRows(context, message, threadId, BlockedLimits.DUPLICATE_WINDOW_MS)
        if (!earlier.isNullOrEmpty()) return SpamOutcome.DUPLICATE
        val placed = runCatching { SpamPlacement.place(context, threadId, message.address, cause, message.date) }
            .getOrNull()
        if (placed != SpamPlacement.Result.PLACED) {
            // A chat cached under another number, or a group, is a planned outcome (not an error).
            if (placed == null) {
                logIssue(context, message, "SPAM_ROW_FAILED")
            } else {
                logIssue(context, message, placed.name, "ALLOW")
            }
            return SpamOutcome.NOT_STORED
        }
        // D-2: on disk before the silent insert, so a replay after a kill knows this row was written silently.
        SilentMarks.mark(context, message)
        // E-2: a text stored nowhere needs no mark.
        val id = runCatching { insertSilently(context, message, threadId, replayed) }
            .onFailure { SilentMarks.clear(context, message) }
            .getOrThrow()
        if (id <= 0L) return SpamOutcome.DUPLICATE.also { SilentMarks.clear(context, message) }
        val outcome = afterSpamInsert(context, message, threadId, id, cause)
        SilentMarks.clear(context, message)
        // D-8: counted only once the text really stays in Spam.
        if (outcome == SpamOutcome.STORED) {
            runCatching { SpamPlacement.raiseUnread(context, threadId) }
            countSpam(context, (judgement.decision() as? FilterDecision.Block)?.ruleId, event = true)
        }
        return outcome
    }

    /** The safety check (R1) and, for an unknown sender, the opt-in notice of the chat. */
    private fun afterSpamInsert(
        context: Context,
        message: PendingSmsEntity,
        threadId: Long,
        id: Long,
        cause: SpamCause,
    ): SpamOutcome {
        val inSpam = runCatching { SpamPlacement.inSpamNow(context, threadId, message.address) }.getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = inSpam)) {
            runCatching { SpamPlacement.dropInvalid(context, threadId) }
            val notify = IncomingSmsProcessor.notifyContextFor(context, message, null)
            val announced = context.announceStored(
                id, message.address, message.body, threadId, message.subscriptionId, message.date, notify,
            )
            // D-10: the store kept the row read: the pending row and the mark stay, the replay tries again.
            check(announced) { "A silent text outside Spam could not be made unread" }
            return SpamOutcome.ANNOUNCED
        }
        // 4.6: "notify me for texts from unknown senders" (shared with the MMS path).
        if (cause == SpamCause.UNKNOWN && AppUnknownNotice.isOn(context)) {
            val shown = BlockedMessage(
                address = message.address, body = message.body, subscriptionId = message.subscriptionId,
                isMms = false, receivedAt = message.date, reason = DecisionReason.UNKNOWN_SENDER,
            )
            BlockedNotifier.show(context, shown, chatId = threadId)
        }
        return SpamOutcome.STORED
    }

    /** The text, read and seen, silent, its chat left archived. Throws when it could not be stored anywhere. */
    private fun insertSilently(context: Context, message: PendingSmsEntity, threadId: Long, replayed: Boolean): Long {
        // J18: only the store write of a replayed text drops a SIM that is gone; the filter saw the SIM it arrived on.
        val stored = if (replayed) message.copy(subscriptionId = MissingSim.simFor(context, message.subscriptionId))
        else message
        return runCatching {
            context.deliverIncomingSms(
                address = stored.address,
                subject = stored.subject,
                body = stored.body,
                date = stored.date,
                subscriptionId = stored.subscriptionId,
                status = stored.status,
                notify = NotifyContext.NONE,
                threadId = threadId,
                read = 1,
                dateSent = stored.dateSent,
                seen = 1,
                unarchive = false,
            )
        }.getOrElse { IncomingSmsProcessor.insertRaw(context, stored, it, read = 1, seen = 1) }
    }

    /** A "blocked" event ([event]) and one more hit of rule [ruleId], as the held-message store counted. */
    private fun countSpam(context: Context, ruleId: Long?, event: Boolean) {
        runCatching {
            val dao = SpamDatabase.getInstance(context).spamDao()
            val now = System.currentTimeMillis()
            if (event) dao.insertEvent(SpamEventEntity(type = SpamEventType.BLOCKED, timestamp = now))
            if (ruleId != null) dao.recordRuleHit(ruleId, now)
        }
    }

    /**
     * The replay found [row] in the store already (the process died after the insert). The text's fact is written
     * again (idempotent). D-2: only a row this path wrote silently ([SilentMarks]) is looked at again: outside Spam it
     * is made unread and announced, in Spam its unread count goes up. A Messages text is never announced twice.
     */
    fun recheckStored(context: Context, row: PendingSmsEntity) {
        SpamEngine.judge(context, row.toIncoming(), "PENDING_REPLAY")
        if (!SilentMarks.has(context, row)) return
        val threadId = runCatching { context.getThreadId(row.address) }.getOrDefault(0L)
        // E-2: without the thread or the store the row is tried again by a later replay; the mark stays.
        check(threadId > 0L) { "No thread for a text written silently" }
        val rows = checkNotNull(inboxRows(context, row, threadId, 0L)) { "The store could not be read" }
        // read = 0: a kill inside announceStored; its "notify due" marker, written first, posts it.
        val stored = rows.firstOrNull()?.takeIf { it.read != 0 }
        if (stored != null) {
            val inSpam = runCatching { SpamPlacement.inSpamNow(context, threadId, row.address) }.getOrDefault(false)
            if (SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = inSpam)) {
                runCatching { SpamPlacement.dropInvalid(context, threadId) }
                val notify = IncomingSmsProcessor.notifyContextFor(context, row, null)
                val announced = context.announceStored(
                    stored.id, row.address, row.body, threadId, row.subscriptionId, row.date, notify,
                )
                check(announced) { "A silent text outside Spam could not be made unread" }
            } else {
                runCatching { SpamPlacement.raiseUnread(context, threadId) }
            }
        }
        SilentMarks.clear(context, row)
    }

    /**
     * Inbox rows of [message]'s sender in [threadId] with its text, dated within [windowMs] of it (D-9: the thread
     * index keeps this off a full scan). Null when the store failed.
     */
    private fun inboxRows(
        context: Context,
        message: PendingSmsEntity,
        threadId: Long,
        windowMs: Long,
    ): List<StoredRow>? = runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.READ),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND " +
                "${Telephony.Sms.DATE} BETWEEN ? AND ?",
            arrayOf(
                "$threadId", message.address, message.body, "${message.date - windowMs}",
                "${message.date + windowMs}",
            ),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(StoredRow(cursor.getLong(0), cursor.getInt(1))) }
        }
    }.getOrNull()
}

/** What the incoming path learns about a sender before and after the verdict. */
internal object ArrivalFacts {

    /** Tests only: the history check of one sender ([HistoryCheck.checkSender]). False = it failed (fail open). */
    @VisibleForTesting
    @Volatile
    internal var checkSender: (Context, String, Long) -> Boolean = { context, address, threadId ->
        HistoryCheck.forApp(context).checkSender(address, threadId)
    }

    /** Tests only: whether the phone's store holds a text the user sent to the number (the 0.4.14 reading). */
    @VisibleForTesting
    @Volatile
    internal var wroteTo: (Context, String) -> ContactLookup = { context, address ->
        SpamEngine.contactChecker(context).sentTo(address)
    }

    /**
     * R2 and B2-14: the sender's history is known before its text is judged. While the first run's history check has
     * not finished (R2), and for a chat not cached yet (B2-14: the check that comes with caching its row would only
     * come after the verdict), the sender is checked now. False when that check failed.
     */
    fun historyKnown(context: Context, address: String, threadId: Long): Boolean {
        val pending = runCatching { FirstRun.state(context).value.historyPending }.getOrDefault(true)
        val cached = !pending && threadId > 0L &&
            runCatching { context.conversationsDB.getConversationWithThreadId(threadId) != null }.getOrDefault(true)
        return cached || checkSender(context, address, threadId)
    }

    /**
     * D-4 (lead decision): a text judged Spam as unknown from a number the user wrote to (a reply sent while uMessage
     * did not run, a contact deleted later) makes the number a member of "People you wrote to", with source HISTORY,
     * so a "Remove" stays removed. Its place is then read again. Anything else, or a failed join, keeps [judgement];
     * null when the sent history could not be read.
     */
    fun wroteToOnArrival(context: Context, address: String, judgement: Judgement): Judgement? {
        val spam = judgement.verdict as? SenderVerdict.Spam
        if (spam?.cause != SpamCause.UNKNOWN) return judgement
        // E-4: the sent history could not be read: not known (the caller fails open), as the history check does.
        val joined = joinIfWroteTo(context, address) ?: return null
        if (!joined) return judgement
        val place = runCatching {
            (UMessage.filter(context) as DefaultSpamFilter).senderJudge.verdict(address)
        }.getOrNull()
        return if (place == null) judgement else judgement.copy(verdict = place)
    }

    /**
     * D-4: true when the user wrote to [address] and it is now a member of "People you wrote to" (source HISTORY);
     * false when it did not (or the join failed); null when the sent history could not be read. Shared by the SMS
     * and the MMS paths (L1-2).
     */
    fun joinIfWroteTo(context: Context, address: String): Boolean? {
        val sent = runCatching { wroteTo(context, address) }.getOrDefault(ContactLookup.UNKNOWN)
        if (sent == ContactLookup.UNKNOWN) return null
        if (sent != ContactLookup.FOUND) return false
        return runCatching {
            val repo = UMessage.repository(context) as DefaultSpamRepository
            runBlocking { repo.joinGroup(AllowGroup.WROTE, address, MemberSource.HISTORY) }
        }.getOrDefault(false)
    }

    /**
     * The decision a Messages text is announced with. D-7: a member of "People you wrote to" is known, as in 0.4.14
     * (open-link action, suggested replies); a sender of a code stays unknown.
     */
    fun messagesDecision(context: Context, address: String, judgement: Judgement?): FilterDecision? {
        val decision = judgement?.decision() ?: return null
        if (decision.reason != DecisionReason.GROUP_MEMBER) return decision
        val wrote = runCatching {
            val repo = UMessage.repository(context) as DefaultSpamRepository
            val readers = SpamThreads.readers(context)
            repo.currentSettings().wroteGroupOn && repo.groupMembership.members().any {
                it.group == AllowGroup.WROTE && SpamThreads.same(readers, it.address, address)
            }
        }.getOrDefault(false)
        return if (wrote) FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION) else decision
    }
}

/**
 * D-1: where a Spam text's chat is, by the rule the lists use: a spam_threads row counts only while the cached chat
 * of its thread has the row's sender as its number ([ConversationsDao.isInSpam]).
 */
internal object SpamPlacement {

    private fun dao(context: Context) = MessagesDatabase.getInstance(context.applicationContext).ConversationsDao()

    /** What [place] did. Anything but [PLACED] sends the text to Messages (unread, announced); the name is logged. */
    enum class Result { PLACED, CHAT_OTHER_NUMBER, CHAT_IS_GROUP }

    /**
     * The row of a Spam text from [address] in [threadId], written before the text. A chat that is not cached yet
     * gets a row with the text's sender (it follows the cached number later); a row of the same sender written
     * another way is kept with its unread count and spam_since (E-5). A cached chat that is in Spam keeps its row as
     * it is (never replaced on arrival). A cached chat in Messages gets a row with its own number when that number is
     * the text's sender; when it is another number (two numbers in one thread, a region mismatch) or a group, no row
     * is written and a row that does not count is removed.
     */
    fun place(context: Context, threadId: Long, address: String, cause: SpamCause, at: Long): Result {
        val db = MessagesDatabase.getInstance(context.applicationContext)
        val readers = SpamThreads.readers(context)
        val result = db.runInTransaction<Result> {
            val dao = db.ConversationsDao()
            val chat = dao.getConversationWithThreadId(threadId)
            val row = dao.getSpamThread(threadId)
            val since = at.coerceAtLeast(1L)
            when {
                chat == null -> {
                    if (row != null && !SpamThreads.same(readers, row.sender, address)) dao.deleteSpamThread(threadId)
                    dao.insertSpamThread(SpamThread(threadId, address, cause, spamSince = since))
                    Result.PLACED
                }
                dao.isInSpam(threadId) -> Result.PLACED
                chat.isGroupConversation -> Result.CHAT_IS_GROUP.also { dao.deleteSpamThread(threadId) }
                !SpamThreads.same(readers, chat.phoneNumber, address) ->
                    Result.CHAT_OTHER_NUMBER.also { dao.deleteSpamThread(threadId) }
                else -> {
                    if (row != null) dao.deleteSpamThread(threadId)
                    dao.insertSpamThread(SpamThread(threadId, chat.phoneNumber, cause, spamSince = since))
                    Result.PLACED
                }
            }
        }
        // H-3 (U9): a chat in Spam has no launcher or share shortcut, also when it got there by this text.
        if (result == Result.PLACED) ShortcutHelper.removeForSpam(context, threadId)
        return result
    }

    /** The safety check: the cached chat is in Spam; for a chat not cached yet, its row names the sender. */
    fun inSpamNow(context: Context, threadId: Long, address: String): Boolean {
        val dao = dao(context)
        return if (dao.getConversationWithThreadId(threadId) != null) {
            dao.isInSpam(threadId)
        } else {
            SpamThreads.isSpamThreadOf(context, threadId, address)
        }
    }

    /** The text is announced in Messages: a row that does not count for the cached chat goes, so no sort reuses it. */
    fun dropInvalid(context: Context, threadId: Long) {
        val dao = dao(context)
        if (dao.getConversationWithThreadId(threadId) != null && !dao.isInSpam(threadId)) dao.deleteSpamThread(threadId)
    }

    /** R12: one spam text more in [threadId]. */
    fun raiseUnread(context: Context, threadId: Long) {
        dao(context).raiseSpamUnread(threadId)
        // J19: the Messages tab number and the Spam list read the table again.
        SpamChanges.noteChanged()
    }
}

/**
 * D-2: texts this path wrote read and silent whose safety check has not finished yet. On disk, so the replay after a
 * kill re-announces only those, never a Messages text the user read. Keyed by a hash of sender, text and time: no
 * content is kept. Marks older than [PendingSmsPolicy.MAX_AGE_MS] are dropped.
 */
internal object SilentMarks {
    private const val PREFS = "umessage_spam_silent"
    private const val KEY_HEX = 32

    fun mark(context: Context, message: PendingSmsEntity) {
        runCatching {
            val prefs = prefs(context)
            val now = System.currentTimeMillis()
            val old = prefs.all.filterValues { at ->
                (at as? Long)?.let { now - it > PendingSmsPolicy.MAX_AGE_MS } ?: true
            }
            prefs.edit().apply {
                old.keys.forEach(::remove)
                putLong(key(message), now)
            }.commit()
        }
    }

    fun has(context: Context, message: PendingSmsEntity): Boolean =
        runCatching { prefs(context).contains(key(message)) }.getOrDefault(false)

    fun clear(context: Context, message: PendingSmsEntity) {
        runCatching { prefs(context).edit().remove(key(message)).commit() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(message: PendingSmsEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${message.address}\u0000${message.body}\u0000${message.date}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_HEX)
    }
}

private fun PendingSmsEntity.toIncoming() = IncomingMessage(
    address = address,
    body = body,
    subscriptionId = subscriptionId,
    isMms = false,
    receivedAt = date,
)

/** One line in the decision log, an error unless [decision] says otherwise (it never holds the text). */
private fun logIssue(context: Context, message: PendingSmsEntity, reason: String, decision: String = "ERROR") {
    runCatching {
        UMessage.log(context).record(
            SpamLogEntry(System.currentTimeMillis(), message.address, message.subscriptionId, decision, reason, "SMS")
        )
    }
}

object PendingSmsQueue {

    /** Rows this process is working on right now (row id -> start time), so replay does not process them twice. */
    private val leases = ConcurrentHashMap<Long, Long>()

    /** Returns the row id, or 0 when the write failed (the caller then just processes the message directly). */
    fun add(context: Context, message: PendingSmsEntity): Long = try {
        SpamDatabase.getInstance(context).spamDao().insertPending(message).also { lease(it) }
    } catch (t: Throwable) {
        0L
    }

    /** Marks [id] as being processed by the caller until [done] (or until the lease runs out). */
    fun lease(id: Long, now: Long = System.currentTimeMillis()) {
        if (id > 0L) leases[id] = now
    }

    /**
     * The caller gave up on [id] for now (its save failed). The row stays in the table; dropping the lease lets the
     * next replay retry it at once instead of waiting [PendingSmsPolicy.LEASE_MS].
     */
    fun release(id: Long) {
        leases.remove(id)
    }

    fun done(context: Context, id: Long) {
        if (id == 0L) return
        runCatching { SpamDatabase.getInstance(context).spamDao().deletePending(id) }
        leases.remove(id)
    }

    /**
     * Re-runs messages left behind by a killed process or a failed save. Blocking call, run off the main thread.
     * A row is removed only after it was stored (blocked or in the inbox). Rows older than
     * [PendingSmsPolicy.MAX_AGE_MS] skip the filter and are delivered straight to the inbox.
     *
     * Returns how many rows are still waiting afterwards (failed now, not due yet or leased by another caller), or
     * -1 when the queue could not be read at all. Either way a value other than 0 means "try again later".
     */
    @Synchronized
    fun replay(context: Context, now: Long = System.currentTimeMillis()): Int {
        // N6: notifications still owed are replayed first, so a broken database does not stop them.
        val notifyFirst = NotifyDue.replay(context, now)
        val dao = try {
            SpamDatabase.getInstance(context).spamDao()
        } catch (t: Throwable) {
            return -1
        }
        val rows = try {
            dao.getPending()
        } catch (t: Throwable) {
            return -1
        }
        val waiting = rows.count { replayRow(context, dao, it, now) }
        // R5: a row stored by the bare insert just now left a marker; post it in this pass, and count it.
        val notifyWaiting = if (rows.isEmpty()) notifyFirst else NotifyDue.replay(context, now)
        return waiting + notifyWaiting
    }

    /** Re-runs one row. Returns true when it is still waiting afterwards. */
    private fun replayRow(context: Context, dao: SpamDao, row: PendingSmsEntity, now: Long): Boolean {
        if (!PendingSmsPolicy.isDue(row.createdAt, now) || PendingSmsPolicy.isLeased(leases[row.id], now)) {
            return true
        }
        lease(row.id, now)
        val expired = PendingSmsPolicy.isExpired(row.createdAt, now)
        val result = runCatching {
            when {
                // Provider truth (R1): the facts again and the safety check on the row that is already stored.
                alreadyInInbox(context, row) -> ProviderTruthSms.recheckStored(context, row)
                // A restored message is the user's decision: no second filter pass, and no "already blocked" test
                // (an identical spam text blocked meanwhile must not stop it from reaching the inbox).
                row.approved -> IncomingSmsProcessor.deliverReadably(context, row, NotifyContext.NONE)
                expired -> if (!alreadyBlocked(dao, row)) {
                    // Reviewer: under provider truth the sender's place still holds (a blocked sender stays silent).
                    ProviderTruthSms.deliverExpired(context, row)
                }
                else -> IncomingSmsProcessor.process(context, row, replayed = true)
            }
        }
        result.exceptionOrNull()?.let { error ->
            val reason = (if (expired) "EXPIRED_" else "") + error.javaClass.simpleName
            runCatching {
                UMessage.log(context).record(
                    SpamLogEntry(now, row.address, row.subscriptionId, "ERROR", reason, "PENDING_REPLAY")
                )
            }
        }
        if (result.isSuccess) {
            done(context, row.id)
            return false
        }
        // Let the next replay retry right away instead of waiting out the lease.
        leases.remove(row.id)
        return true
    }

    /** The row was stored as blocked already (the process died before the pending row was deleted). */
    private fun alreadyBlocked(dao: SpamDao, row: PendingSmsEntity): Boolean = runCatching {
        dao.findDuplicateBlocked(
            BlockedLimits.limitAddress(row.address),
            BlockedLimits.limitBody(row.body),
            false,
            row.date - BlockedLimits.DUPLICATE_WINDOW_MS,
            row.date + BlockedLimits.DUPLICATE_WINDOW_MS,
        ) != null
    }.getOrDefault(false)

    /** The process may have died after the inbox insert and before the row was deleted. */
    internal fun alreadyInInbox(context: Context, row: PendingSmsEntity): Boolean = runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ?",
            arrayOf(row.address, row.body, row.date.toString()),
            null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}
