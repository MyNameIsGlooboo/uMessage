package com.glooboo.umessage.spam.data

import android.content.Context
import android.provider.Telephony
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.annotation.VisibleForTesting
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.receivers.PendingSmsRetry
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.HistoryCheck
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.SpamSafetyCheck
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
    fun process(context: Context, message: PendingSmsEntity) {
        if (ProviderTruthFlag.enabled) return ProviderTruthSms.process(context, message)
        val incoming = IncomingMessage(
            address = message.address,
            body = message.body,
            subscriptionId = message.subscriptionId,
            isMms = false,
            receivedAt = message.date,
        )
        val decision = SpamEngine.evaluate(context, incoming)
        val block = decision as? FilterDecision.Block
        if (block != null && SpamEngine.storeBlocked(context, incoming, block)) return
        deliver(context, message, notifyContextFor(context, message, decision))
        SpamEngine.allowSenderOfKeyword(context, incoming, decision)
        // Only now, with the SMS safely in the inbox: blocked spam never reaches this line.
        forward(context, message)
    }

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
        var reason = (decision as? FilterDecision.Allow)?.reason
        // Provider truth: the hidden "wrote to" check is gone; such a sender is a member of "People you wrote to".
        if (!ProviderTruthFlag.enabled && reason == DecisionReason.MODE_OFF &&
            SpamEngine.hasWrittenTo(context, message.address)
        ) {
            reason = DecisionReason.KNOWN_CONVERSATION
        }
        val setting = runCatching { SpamEngine.repository(context).currentSettings().unknownInboxNotify }
            .getOrDefault(SpamSettings().unknownInboxNotify)
        val hasCode = OtpCodeExtractor.extract(message.body) != null
        return NotifyContext(UnknownNotifyPolicy.modeFor(reason, setting, hasCode), reason)
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
 * Provider truth (6.1): the incoming SMS path behind [ProviderTruthFlag]. The sender's history is known first (R2,
 * B2-14), then the text's fact is written (R1, [SpamEngine.judge]), then the one verdict of the sender says where the
 * text goes:
 *
 * - Messages: as before, unread, with the notification and its "notify due" marker, then the forward rules.
 * - Spam: the chat's spam_threads row first (unread + 1), then the text read and seen, with no notification, no
 *   unarchive and no forwarding; a "blocked" event and the rule's hit. Cause UNKNOWN with the opt-in option on: the
 *   quiet notice of that chat ([BlockedNotifier.show]). A blocked sender never notifies.
 *
 * The safety check after the insert ([SpamSafetyCheck]): a text stored read and silent whose chat is not in Spam is
 * made unread and announced. Any error on the way is fail open: Messages, unread, announced.
 */
internal object ProviderTruthSms {

    /** How a text for Spam ended. */
    private enum class SpamOutcome { STORED, DUPLICATE, ANNOUNCED, NOT_STORED }

    /** One inbox row of the phone's store. */
    private class StoredRow(val id: Long, val threadId: Long, val read: Int)

    /** Tests only: the history check of a sender before its text is judged. False = it failed (fail open). */
    @VisibleForTesting
    @Volatile
    internal var checkHistory: (Context, String, Long) -> Boolean = { context, address, threadId ->
        historyKnown(context, address, threadId)
    }

    /** Stores [message] where its sender's chat is. Throws only when it could not be stored at all. */
    fun process(context: Context, message: PendingSmsEntity) {
        val threadId = runCatching { context.getThreadId(message.address) }.getOrDefault(0L)
        val known = runCatching { checkHistory(context, message.address, threadId) }.getOrDefault(false)
        if (!known) logIssue(context, message, "HISTORY_CHECK_FAILED")
        val judgement = if (known) SpamEngine.judge(context, message.toIncoming()) else null
        val spam = judgement?.verdict as? SenderVerdict.Spam
        if (judgement != null && spam != null) {
            when (storeInSpam(context, message, threadId, judgement, spam.cause)) {
                SpamOutcome.STORED, SpamOutcome.DUPLICATE -> return
                // The chat is in Messages after all: the text is announced there, and forwarded like one.
                SpamOutcome.ANNOUNCED -> return IncomingSmsProcessor.forward(context, message)
                // Fail open: no Spam row, so the text goes to Messages, unread and announced.
                SpamOutcome.NOT_STORED -> Unit
            }
        }
        val decision = if (spam == null) judgement?.decision() else null
        val notify = IncomingSmsProcessor.notifyContextFor(context, message, decision)
        IncomingSmsProcessor.deliver(context, message, notify)
        // A-11: the allowed word that let the text in counts it.
        if (spam == null) countSpam(context, judgement?.wordRuleId, event = false)
        IncomingSmsProcessor.forward(context, message)
    }

    /** 6.1, the Spam verdict: the row first, then the text read and silent, then the safety check. */
    private fun storeInSpam(
        context: Context,
        message: PendingSmsEntity,
        threadId: Long,
        judgement: Judgement,
        cause: SpamCause,
    ): SpamOutcome {
        if (threadId <= 0L) return SpamOutcome.NOT_STORED
        // As the held-message store did, for Spam only: the same text from the same sender within ten seconds is one.
        val earlier = inboxRows(context, message, BlockedLimits.DUPLICATE_WINDOW_MS)
        if (!earlier.isNullOrEmpty()) return SpamOutcome.DUPLICATE
        val noted = runCatching { SpamThreads.noteArrival(context, threadId, message.address, cause, message.date) }
        if (noted.isFailure) return SpamOutcome.NOT_STORED.also { logIssue(context, message, "SPAM_ROW_FAILED") }
        val id = insertSilently(context, message, threadId)
        if (id <= 0L) return SpamOutcome.DUPLICATE
        countSpam(context, (judgement.decision() as? FilterDecision.Block)?.ruleId, event = true)
        return afterSpamInsert(context, message, threadId, id, cause)
    }

    /** The safety check (R1) and, for an unknown sender, the opt-in notice of the chat. */
    private fun afterSpamInsert(
        context: Context,
        message: PendingSmsEntity,
        threadId: Long,
        id: Long,
        cause: SpamCause,
    ): SpamOutcome {
        val inSpam = runCatching { SpamThreads.isSpamThreadOf(context, threadId, message.address) }.getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = inSpam)) {
            context.announceStored(id, message.address, message.body, threadId, message.subscriptionId, message.date)
            return SpamOutcome.ANNOUNCED
        }
        // 4.6: "notify me for texts from unknown senders", stored under the old key notify_on_block.
        val noticeOn = runCatching { SpamEngine.repository(context).currentSettings().notifyOnBlock }
            .getOrDefault(false)
        if (cause == SpamCause.UNKNOWN && noticeOn) {
            val shown = BlockedMessage(
                address = message.address, body = message.body, subscriptionId = message.subscriptionId,
                isMms = false, receivedAt = message.date, reason = DecisionReason.UNKNOWN_SENDER,
            )
            BlockedNotifier.show(context, shown, chatId = threadId)
        }
        return SpamOutcome.STORED
    }

    /** The text, read and seen, silent, its chat left archived. Throws when it could not be stored anywhere. */
    private fun insertSilently(context: Context, message: PendingSmsEntity, threadId: Long): Long = runCatching {
        context.deliverIncomingSms(
            address = message.address,
            subject = message.subject,
            body = message.body,
            date = message.date,
            subscriptionId = message.subscriptionId,
            status = message.status,
            notify = NotifyContext.NONE,
            threadId = threadId,
            read = 1,
            dateSent = message.dateSent,
            seen = 1,
            unarchive = false,
        )
    }.getOrElse { IncomingSmsProcessor.insertRaw(context, message, it, read = 1, seen = 1) }

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
     * again (idempotent), and a row stored read and silent whose chat is not in Spam is made unread and announced.
     */
    fun recheckStored(context: Context, row: PendingSmsEntity) {
        SpamEngine.judge(context, row.toIncoming(), "PENDING_REPLAY")
        val stored = inboxRows(context, row, 0L)?.firstOrNull() ?: return
        if (stored.read == 0) return
        val inSpam = runCatching { SpamThreads.isSpamThreadOf(context, stored.threadId, row.address) }
            .getOrDefault(false)
        if (SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = inSpam)) {
            context.announceStored(stored.id, row.address, row.body, stored.threadId, row.subscriptionId, row.date)
        }
    }

    /** Inbox rows of [message]'s sender with its text, dated within [windowMs] of it. Null when the store failed. */
    private fun inboxRows(context: Context, message: PendingSmsEntity, windowMs: Long): List<StoredRow>? =
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.READ),
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} BETWEEN ? AND ?",
                arrayOf(message.address, message.body, "${message.date - windowMs}", "${message.date + windowMs}"),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(StoredRow(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2)))
                }
            }
        }.getOrNull()

    /**
     * R2 and B2-14: the sender's history is known before its text is judged. While the first run's history check has
     * not finished, the sender is checked now ([HistoryCheck.onTheSpot]). A chat not cached yet is new to the app: its
     * sender is checked now too, as the check that comes with caching its row would only come after the verdict.
     */
    private fun historyKnown(context: Context, address: String, threadId: Long): Boolean {
        if (!HistoryCheck.onTheSpot(context, address, threadId)) return false
        if (FirstRun.state(context).value.historyPending) return true
        val cached = threadId > 0L &&
            runCatching { context.conversationsDB.getConversationWithThreadId(threadId) != null }.getOrDefault(true)
        return cached || HistoryCheck.forApp(context).checkSender(address, threadId)
    }
}

private fun PendingSmsEntity.toIncoming() = IncomingMessage(
    address = address,
    body = body,
    subscriptionId = subscriptionId,
    isMms = false,
    receivedAt = date,
)

/** One error line in the decision log (it never holds the text). */
private fun logIssue(context: Context, message: PendingSmsEntity, reason: String) {
    runCatching {
        UMessage.log(context).record(
            SpamLogEntry(System.currentTimeMillis(), message.address, message.subscriptionId, "ERROR", reason, "SMS")
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
                alreadyInInbox(context, row) ->
                    if (ProviderTruthFlag.enabled) ProviderTruthSms.recheckStored(context, row)
                // A restored message is the user's decision: no second filter pass, and no "already blocked" test
                // (an identical spam text blocked meanwhile must not stop it from reaching the inbox).
                row.approved -> IncomingSmsProcessor.deliver(context, row, NotifyContext.NONE)
                expired -> if (!alreadyBlocked(dao, row)) IncomingSmsProcessor.deliver(context, row)
                else -> IncomingSmsProcessor.process(context, row)
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
