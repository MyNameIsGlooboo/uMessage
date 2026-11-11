package com.glooboo.umessage.spam.data

import android.content.Context
import android.provider.Telephony
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.SpamEngine
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
        // Only now, with the SMS safely in the inbox: blocked spam never reaches this line. Never throws, so a
        // forwarding problem cannot make the caller keep (and later replay) a message that is already delivered.
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
        if (reason == DecisionReason.MODE_OFF && SpamEngine.hasWrittenTo(context, message.address)) {
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
            insertRaw(context, message, first)
        }
    }

    /** Last resort: a bare provider insert without the cache, conversation or notification steps. */
    internal fun insertRaw(context: Context, message: PendingSmsEntity, cause: Throwable? = null): Long {
        if (PendingSmsQueue.alreadyInInbox(context, message)) return -1L
        val threadId = runCatching { context.getThreadId(message.address) }.getOrDefault(0L)
        return try {
            context.insertSmsOrThrow(
                address = message.address,
                subject = message.subject,
                body = message.body,
                date = message.date,
                read = 0,
                threadId = threadId,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                subscriptionId = message.subscriptionId,
                dateSent = message.dateSent,
            )
        } catch (t: Throwable) {
            throw SmsInsertException("SMS could not be stored", cause ?: t)
        }
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
        var waiting = 0
        for (row in rows) {
            if (!PendingSmsPolicy.isDue(row.createdAt, now) || PendingSmsPolicy.isLeased(leases[row.id], now)) {
                waiting++
                continue
            }
            lease(row.id, now)
            val expired = PendingSmsPolicy.isExpired(row.createdAt, now)
            val result = runCatching {
                when {
                    alreadyInInbox(context, row) -> Unit
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
            } else {
                // Let the next replay retry right away instead of waiting out the lease.
                leases.remove(row.id)
                waiting++
            }
        }
        return waiting
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
