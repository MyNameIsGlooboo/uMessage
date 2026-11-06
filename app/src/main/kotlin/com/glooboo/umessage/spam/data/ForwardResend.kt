package com.glooboo.umessage.spam.data

import android.content.Context
import android.util.Log
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.helpers.NotificationHelper
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.SmsException
import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.engine.ForwardBudget
import com.glooboo.umessage.spam.engine.ForwardDailyLimit
import com.glooboo.umessage.spam.engine.ForwardPlanner
import com.glooboo.umessage.spam.engine.SpamEngine

/**
 * The shared state of the cost guards (M4): one lock held while the day's and the hour's forwards are counted and the
 * rows that use them are written, by new forwards ([ForwardDelivery]) and retries ([ForwardResend]) alike.
 */
internal object ForwardLimits {
    /** Held while the limits are counted and the rows that use them are written. */
    val lock = Any()

    /** Clock for the limits; tests move it. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    fun dailyLimit(context: Context): Int = runCatching { W3aPrefs.get(context).dailyForwardLimit }.getOrDefault(0)

    /** The log rows the limits count, or null when the log cannot be read (the limits then fail closed). */
    fun rows(dao: ForwardLogDao?, now: Long): List<ForwardLogEntry>? = dao?.let { d ->
        runCatching { d.touchedSince(ForwardBudget.since(now)).map { it.toModel() } }.getOrNull()
    }

    /** The first time the daily limit stops a forward on a day, a notification says so. */
    fun notifyDailyLimit(context: Context, now: Long, limit: Int) {
        runCatching {
            val prefs = W3aPrefs.get(context)
            val day = ForwardDailyLimit.dayNumber(now)
            if (prefs.dailyLimitNotifiedDay != day) {
                prefs.dailyLimitNotifiedDay = day
                NotificationHelper(context).showForwardLimitNotification(limit)
            }
        }
    }
}

/** Sending a forward again (Retry in the Forwarded tab), the editor's test SMS, and reading a forward's original. */
object ForwardResend {
    private const val TAG = "uMessageForward"

    /** What [retry] did with one failed forward. */
    enum class RetryResult {
        SENT, FAILED, ORIGINAL_MISSING, NOT_FAILED,

        /** Not sent: "pause all forwarding" is on. */
        PAUSED,

        /** Not sent: the forward's rule was deleted or turned off. */
        RULE_OFF,

        /** Not sent: the rule's hourly cap or the daily limit is reached, or the log could not be read. */
        LIMITED,
    }

    /**
     * Sends a failed forward again to its target, from the SIM it used (M2: only while forwarding is not paused, its
     * rule is still there and on, and the limits allow one more SMS; a retry counts against them). The text is read
     * again from the saved original (found by sender and time), never from a copy. Call off the main thread. Never
     * throws.
     */
    fun retry(context: Context, logId: Long): RetryResult {
        val app = context.applicationContext
        if (!ForwardingAvailability.isAvailable(app)) return RetryResult.RULE_OFF
        val dao = runCatching { SpamDatabase.getInstance(app).forwardLogDao() }.getOrNull()
        val row = dao?.let { d -> runCatching { d.get(logId) }.getOrNull()?.toModel() }
        if (dao == null || row == null || row.status != ForwardStatus.FAILED) return RetryResult.NOT_FAILED
        val body = originalBody(app, row.sourceAddress, row.sourceDate, row.sourceIsMms)
            ?: return RetryResult.ORIGINAL_MISSING
        val recorder = ForwardLogRecorder(dao, ForwardLimits.clock)
        val blocked = reserveRetry(app, dao, recorder, row, body)
        return blocked ?: sendRetry(app, recorder, row, body)
    }

    /**
     * Checks the switches and limits for a retry of [row] and, when it may go out, marks it as a new attempt. A
     * [body] that holds a code is not sent again by a rule that leaves codes out (it counts as "rule off").
     */
    private fun reserveRetry(
        app: Context,
        dao: ForwardLogDao,
        recorder: ForwardLogRecorder,
        row: ForwardLogEntry,
        body: String,
    ): RetryResult? {
        val paused = runCatching { NavPrefs.get(app).forwardPaused }.getOrDefault(true)
        val rule = runCatching { SpamEngine.forwardRepository(app).currentRules() }.getOrDefault(emptyList())
            .firstOrNull { it.id == row.ruleId }
            ?.takeUnless { !it.includeCodes && ForwardPlanner.looksLikeCode(body) }
        val block = synchronized(ForwardLimits.lock) {
            val now = ForwardLimits.clock()
            val rows = ForwardLimits.rows(dao, now)
            ForwardBudget.retryBlock(row, rule, paused, rows, now, ForwardLimits.dailyLimit(app))
                // Marking the retry inside the lock makes it count against the limits for the next send.
                .also { if (it == null) recorder.retryStarted(row.id) }
        }
        return when (block) {
            ForwardBudget.RetryBlock.PAUSED -> RetryResult.PAUSED
            ForwardBudget.RetryBlock.RULE_OFF -> RetryResult.RULE_OFF
            ForwardBudget.RetryBlock.LIMIT -> RetryResult.LIMITED
            null -> null
        }
    }

    private fun sendRetry(app: Context, recorder: ForwardLogRecorder, row: ForwardLogEntry, body: String): RetryResult {
        // A retry hides the code the same way the first send did, and fails closed the same way.
        val masker = runCatching { W3aPrefs.get(app).forwardCodeMasker() }.getOrNull()
        val code = masker != null && ForwardPlanner.looksLikeCode(body)
        val sendBody = if (code && masker != null) ForwardPlanner.failClosed(masker, body) else body
        val sender = ForwardDelivery.senderDisplay(app, row.sourceAddress)
        val text = ForwardFormat.format(sender, sendBody, row.sourceIsMms)
        val error = runCatching { ForwardDelivery.sender.send(app, text, row.target, row.subscriptionId, row.id) }
            .exceptionOrNull()
        if (error == null) {
            recorder.sent(row.id)
            return RetryResult.SENT
        }
        Log.w(TAG, "Retrying a forward failed", error)
        recorder.failed(row.id, (error as? SmsException)?.errorCode?.toString() ?: error.javaClass.simpleName)
        return RetryResult.FAILED
    }

    /**
     * ID-07: one test SMS to each of [targets], from [subscriptionId] (null = the phone's default SMS SIM). It carries
     * the forward prefix, so a phone that forwards it on (or back) treats it as a forward and stops there. Not written
     * to the forward log. Call off the main thread. Returns how many were handed to the radio; never throws.
     */
    fun sendTest(context: Context, targets: List<String>, subscriptionId: Int?, from: String, text: String): Int {
        val app = context.applicationContext
        if (!ForwardingAvailability.isAvailable(app)) return 0
        val message = ForwardFormat.format(from, text, isMms = false)
        val sent = targets.count { target ->
            val error = runCatching { ForwardDelivery.sender.send(app, message, target, subscriptionId, null) }
                .exceptionOrNull()
            if (error != null) Log.w(TAG, "A test forward could not be sent", error)
            error == null
        }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
        return sent
    }

    /** The saved text of the message a forward came from, or null when it is gone. Call off the main thread. */
    fun originalBody(context: Context, address: String, receivedAtMs: Long, isMms: Boolean): String? = runCatching {
        val threadId = context.getThreadId(address)
        val seconds = receivedAtMs / MS_PER_SECOND
        context.messagesDB.getThreadMessages(threadId)
            .firstOrNull { it.isMMS == isMms && it.isReceivedMessage() && kotlin.math.abs(it.date - seconds) <= 1 }
            ?.body
    }.getOrNull()

    private const val MS_PER_SECOND = 1000
}
