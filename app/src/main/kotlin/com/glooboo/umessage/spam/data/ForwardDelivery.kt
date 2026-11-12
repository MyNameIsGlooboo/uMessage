package com.glooboo.umessage.spam.data

import android.content.Context
import android.util.Log
import com.klinker.android.send_message.Settings
import org.fossify.commons.helpers.SimpleContactsHelper
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.messagingUtils
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.SmsException
import com.glooboo.umessage.messaging.notifySendFailedInBackground
import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.ForwardLogPolicy
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ForwardBudget
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.ForwardPlan
import com.glooboo.umessage.spam.engine.ForwardSkip
import com.glooboo.umessage.spam.engine.PlannedForward
import com.glooboo.umessage.spam.engine.SpamEngine

/** Sends one forward. Throws when it could not be handed to the radio. */
fun interface ForwardSmsSender {
    /** [logId] is the forward-log row of this send (null for a test SMS); the sent report carries it back. */
    fun send(context: Context, text: String, target: String, subscriptionId: Int?, logId: Long?)
}

/**
 * The app's own SMS path ([com.glooboo.umessage.messaging.MessagingUtils.sendSmsMessage]): the forward is saved in the
 * target's thread like any sent SMS, long text goes out as a multipart SMS (never as MMS: forwarding needs no mobile
 * data), and the SIM is picked per send. A failure the radio reports later goes through SmsStatusSentReceiver, which
 * marks the row failed, shows the "message not sent" notification and logs the forward
 * ([ForwardDelivery.onRadioFailure]).
 */
object SystemForwardSender : ForwardSmsSender {
    override fun send(context: Context, text: String, target: String, subscriptionId: Int?, logId: Long?) {
        // Last gate before the radio: a build without forwarding would never send a forward, whoever asks.
        check(ForwardingAvailability.isAvailable(context)) { "Forwarding is not available in this build" }
        context.messagingUtils.sendSmsMessage(
            text = text,
            addresses = setOf(target),
            subId = subscriptionId ?: Settings.DEFAULT_SUBSCRIPTION_ID,
            requireDeliveryReport = runCatching { context.config.enableDeliveryReports }.getOrDefault(false),
            forwardLogId = logId,
        )
    }
}

/**
 * Forwarding of inbox messages (wave 8). Called once a message is in the inbox, on the receiver's background thread
 * (inside its goAsync window): blocked spam never gets here. Each send is handed to the radio right away, which keeps
 * sending after this process is gone, so no background job is needed. Never throws.
 *
 * Every forward, sent or failed, and every skip of a matching rule (a code, a loop, a limit) is written to the decision
 * log with rule, target and result. No message text is logged.
 *
 * The cost guards (M4): counting the day's and the hour's forwards, deciding what may go out and writing those rows
 * happen under one lock, so two messages handled at the same moment cannot both take the last allowed send. A log that
 * cannot be read or written stops the send (fail closed): without it neither limit can be kept.
 */
object ForwardDelivery {
    private const val TAG = "uMessageForward"
    const val SOURCE = "FORWARD"
    private const val MAX_SKIP_LOGS = 3

    @Volatile internal var sender: ForwardSmsSender = SystemForwardSender

    fun onInboxMessage(context: Context, message: ForwardCandidate) {
        if (!ForwardingAvailability.isAvailable(context)) return
        try {
            val app = context.applicationContext
            val rules = SpamEngine.forwardRepository(app).currentRules()
            if (rules.none { it.enabled }) return
            val plan = SpamEngine.forwardPlanner(app).plan(message, rules) { senderDisplay(app, it) }
            run(app, message, plan)
        } catch (t: Throwable) {
            Log.e(TAG, "Forwarding failed", t)
            log(context, message, "FORWARD_FAILED", "ERROR_" + t.javaClass.simpleName)
        }
    }

    private fun run(context: Context, message: ForwardCandidate, plan: ForwardPlan) {
        // spam-19: at most a few skip entries per message, so many forward rules cannot push BLOCK and ALLOW entries
        // out of the shared decision log.
        plan.skipped.take(MAX_SKIP_LOGS).forEach { skip ->
            val to = skip.target?.let { " → $it" }.orEmpty()
            log(context, message, "FORWARD_SKIPPED", "#${skip.ruleId}$to ${skip.reason.name}")
        }
        if (plan.sends.isEmpty()) return
        // The global "pause all forwarding" switch (Forwarded tab, Settings): rules stay, nothing is sent.
        if (runCatching { NavPrefs.get(context).forwardPaused }.getOrDefault(false)) {
            plan.sends.take(MAX_SKIP_LOGS).forEach {
                log(context, message, "FORWARD_SKIPPED", "#${it.ruleId} → ${it.target} PAUSED")
            }
            return
        }
        val reserved = synchronized(ForwardLimits.lock) { reserve(context, message, plan.sends) }
        for ((send, rowId) in reserved) deliver(context, message, send, rowId)
        if (reserved.isNotEmpty()) {
            runCatching { refreshMessages() }
            runCatching { refreshConversations() }
        }
    }

    private fun logSkip(context: Context, message: ForwardCandidate, send: PlannedForward, reason: String) =
        log(context, message, "FORWARD_SKIPPED", "#${send.ruleId} → ${send.target} $reason")

    /**
     * Call with [ForwardLimits.lock] held. Splits [sends] by the hourly cap and the daily limit, writes a SKIPPED row
     * for each one a limit stopped and a PENDING row for each one that may go out, and returns those with their row
     * ids. A send whose row cannot be written is not sent.
     */
    private fun reserve(
        context: Context,
        message: ForwardCandidate,
        sends: List<PlannedForward>,
    ): List<Pair<PlannedForward, Long>> {
        val dao = runCatching { SpamDatabase.getInstance(context).forwardLogDao() }.getOrNull()
        val now = ForwardLimits.clock()
        val limit = ForwardLimits.dailyLimit(context)
        val split = ForwardBudget.split(sends, ForwardLimits.rows(dao, now), now, limit)
        val recorder = dao?.let { ForwardLogRecorder(it, ForwardLimits.clock) }
        split.rateLimited.forEach {
            logSkip(context, message, it, ForwardSkip.RATE_LIMITED.name)
            recorder?.beginRow(message, it, ForwardStatus.SKIPPED, ForwardLogPolicy.SKIP_RATE_LIMITED)
        }
        split.overDay.forEach {
            logSkip(context, message, it, ForwardLogPolicy.SKIP_DAILY_LIMIT)
            recorder?.beginRow(message, it, ForwardStatus.SKIPPED, ForwardLogPolicy.SKIP_DAILY_LIMIT)
        }
        if (split.overDay.isNotEmpty()) ForwardLimits.notifyDailyLimit(context, now, limit)
        return split.allowed.mapNotNull { send ->
            val id = recorder?.beginRow(message, send, ForwardStatus.PENDING, null)
            // Not counted means not sent: the next message could not see it against the limits.
            if (id == null) logSkip(context, message, send, ForwardSkip.RATE_LIMITED.name)
            id?.let { send to it }
        }
    }

    private fun ForwardLogRecorder.beginRow(
        message: ForwardCandidate,
        send: PlannedForward,
        status: ForwardStatus,
        code: String?,
    ) =
        begin(
            ruleId = send.ruleId,
            sourceAddress = message.address,
            sourceDate = message.receivedAt,
            sourceIsMms = message.isMms,
            target = send.target,
            subscriptionId = send.subscriptionId,
            status = status,
            errorCode = code,
        )

    private fun recorder(context: Context): ForwardLogRecorder? =
        runCatching { ForwardLogRecorder(SpamDatabase.getInstance(context).forwardLogDao(), ForwardLimits.clock) }
            .getOrNull()

    private fun deliver(context: Context, message: ForwardCandidate, send: PlannedForward, rowId: Long) {
        val recorder = recorder(context)
        val error = runCatching { sender.send(context, send.text, send.target, send.subscriptionId, rowId) }
            .exceptionOrNull()
        if (error == null) {
            recorder?.sent(rowId)
            log(context, message, "FORWARD", "#${send.ruleId} → ${send.target} SENT")
            return
        }
        Log.w(TAG, "Forward to a target could not be sent", error)
        val code = (error as? SmsException)?.errorCode?.toString() ?: error.javaClass.simpleName
        recorder?.failed(rowId, code)
        log(context, message, "FORWARD_FAILED", "#${send.ruleId} → ${send.target} FAILED_$code")
        runCatching { context.notifySendFailedInBackground(listOf(send.target)) }
    }

    /**
     * The radio reported that a sent SMS failed. When it carries a forward-log row ([logId]), exactly that forward is
     * marked failed (H2). A forward (it starts with the forward prefix) also goes in the decision log; the "message
     * not sent" notification is shown by the caller as for any SMS.
     */
    fun onRadioFailure(context: Context, address: String, body: String?, subscriptionId: Int, logId: Long? = null) {
        if (!ForwardingAvailability.isAvailable(context)) return
        if (logId == null && (body == null || !ForwardFormat.isForward(body))) return
        if (logId != null) recorder(context)?.radioFailed(logId)
        val candidate = ForwardCandidate(address, "", subscriptionId)
        log(context, candidate, "FORWARD_FAILED", "→ $address ${ForwardLogRecorder.RADIO_FAILED}")
    }

    /** The contact's name, else the number (or sender id) itself. */
    internal fun senderDisplay(context: Context, address: String): String =
        runCatching { SimpleContactsHelper(context).getNameFromPhoneNumber(address) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: address

    private fun log(context: Context, message: ForwardCandidate, decision: String, reason: String) {
        runCatching {
            UMessage.log(context).record(
                SpamLogEntry(
                    System.currentTimeMillis(), message.address, message.subscriptionId, decision, reason, SOURCE,
                ),
            )
        }
    }
}
