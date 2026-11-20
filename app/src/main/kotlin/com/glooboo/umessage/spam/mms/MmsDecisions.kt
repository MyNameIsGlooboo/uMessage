package com.glooboo.umessage.spam.mms

import android.content.Context
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.SpamEngine

/**
 * E-13, provider truth: one decision-log row per MMS. The push gate, a group placement and a failed download ask the
 * filter with [probe], which writes nothing and logs nothing; the MMS's one row is written where its place is final:
 * SpamEngine.judge for a one-to-one MMS, [log] for the others. Never throws.
 */
object MmsDecisions {

    /** The sender's verdict without writing or logging anything; null when the filter failed. */
    fun probe(context: Context, message: IncomingMessage): FilterDecision? =
        runCatching { UMessage.filter(context.applicationContext).evaluate(message) }.getOrNull()

    /** Writes the row of [message]: [decision] "ALLOW", "BLOCK", "DELIVER" or "ERROR", with [source]. */
    fun log(
        context: Context,
        message: IncomingMessage,
        decision: String,
        reason: String,
        source: String,
        ruleId: Long? = null,
    ) {
        runCatching {
            val app = context.applicationContext
            UMessage.log(app).record(
                SpamLogEntry(
                    System.currentTimeMillis(), message.address, message.subscriptionId, decision, reason, source,
                    ruleId, ruleId?.let { ruleLabel(app, it) }.orEmpty(),
                )
            )
        }
    }

    /** The short label of rule [id]; empty when it cannot be read. */
    private fun ruleLabel(context: Context, id: Long): String = runCatching {
        SpamEngine.repository(context).currentRules().firstOrNull { it.id == id }?.let { DecisionRuleText.label(it) }
    }.getOrNull().orEmpty()
}
