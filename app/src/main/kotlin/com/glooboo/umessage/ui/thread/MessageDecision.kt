package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.R
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamRule
import kotlin.math.abs

/** What the decision log says about one received message: the filter's decision and any forwards of it. */
data class MessageDecision(val decision: SpamLogEntry?, val forwards: List<SpamLogEntry>)

/**
 * Finds a received message's entries in the decision log for Message details (TH-13). The log keeps no message ids
 * (and no bodies), so a message is matched by its sender and its time: the decision closest to the received time,
 * within [WINDOW_MS], and the forwards logged around it. Older messages than the log keeps (a week) find nothing.
 * Phone numbers are compared as phone numbers, so call it off the main thread.
 */
object MessageDecisions {
    /** How far apart the provider's received time and the log's decision time may be. */
    const val WINDOW_MS = 3 * 60_000L

    private val FILTER_DECISIONS = setOf("ALLOW", "BLOCK")
    private val FORWARD_DECISIONS = setOf("FORWARD", "FORWARD_FAILED", "FORWARD_SKIPPED")

    fun find(entries: List<SpamLogEntry>, sender: String, receivedMillis: Long): MessageDecision {
        val nearby = entries.filter { abs(it.time - receivedMillis) <= WINDOW_MS && sameSender(it.sender, sender) }
        val decision = nearby.filter { it.decision in FILTER_DECISIONS }.minByOrNull { abs(it.time - receivedMillis) }
        val forwards = nearby.filter { it.decision in FORWARD_DECISIONS }.sortedBy { it.time }
        return MessageDecision(decision, forwards)
    }

    /** Sender names ignoring case; numbers as phone numbers ("+1555..." and "0555..." are the same person). */
    fun sameSender(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val type = if (a.any { it.isLetter() } || b.any { it.isLetter() }) RuleType.SENDER_ID else RuleType.NUMBER
        return isSameSender(a, b, type)
    }

    /** The label of the reason the engine logged, or null for a reason without one (an error's class name). */
    fun reasonLabel(reason: String): Int? = when (reason) {
        "ALLOW_RULE" -> R.string.ui_log_reason_allow_rule
        "BLOCK_RULE" -> R.string.ui_log_reason_block_rule
        "CONTACT" -> R.string.ui_log_reason_contact
        "OTP_EXEMPTION" -> R.string.ui_log_reason_otp
        "UNKNOWN_SENDER" -> R.string.ui_log_reason_unknown_sender
        "MODE_OFF" -> R.string.ui_log_reason_mode_off
        "NO_MATCH" -> R.string.ui_log_reason_no_match
        "CONTACTS_UNAVAILABLE" -> R.string.ui_log_reason_contacts_unavailable
        "KNOWN_CONVERSATION" -> R.string.ui_log_reason_known_conversation
        "FOREIGN_NUMBER" -> R.string.region_reason_foreign
        else -> null
    }

    /**
     * The rule behind [entry]: the block rule the log names, or for an allow-rule decision the allow rule that names
     * [sender] now (allow decisions do not log a rule id). Null when no rule decided.
     */
    fun ruleLabel(entry: SpamLogEntry, sender: String, rules: List<SpamRule>): String? = when (entry.reason) {
        "BLOCK_RULE" -> DecisionRuleText.ruleLabel(entry, rules)
        "ALLOW_RULE" -> rules.firstOrNull { it.kind == ListKind.ALLOW && ruleNames(it, sender) }
            ?.let(DecisionRuleText::label)
            ?.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun ruleNames(rule: SpamRule, sender: String): Boolean {
        val senderRule = rule.type == RuleType.NUMBER || rule.type == RuleType.SENDER_ID
        return senderRule && isSameSender(rule.pattern, sender, rule.type)
    }
}
