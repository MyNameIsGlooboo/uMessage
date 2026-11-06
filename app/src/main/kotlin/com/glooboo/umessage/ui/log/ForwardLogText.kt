package com.glooboo.umessage.ui.log

import android.content.res.Resources
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ForwardRule

/**
 * Readable text for the forward entries of the decision log. The log keeps the compact codes ForwardDelivery writes
 * ("#1 CODE_EXCLUDED", "#1 → 5554999 SENT", "→ 5554999 RADIO_FAILED", "ERROR_X"); they are turned into sentences only
 * when shown, so stored entries never change.
 */
object ForwardLogText {

    enum class Outcome { SENT, FAILED, RADIO_FAILED, CODE_EXCLUDED, LOOP_FORWARD, LOOP_SENDER, SIM_FALLBACK, RATE_LIMITED, ERROR, UNKNOWN }

    data class Parsed(val outcome: Outcome, val ruleId: Long? = null, val target: String? = null, val detail: String = "")

    private val shape = Regex("""^(?:#(\d+)\s+)?(?:→\s+(\S+)\s+)?(\S+)$""")

    fun isForward(decision: String) = decision == "FORWARD" || decision == "FORWARD_FAILED" || decision == "FORWARD_SKIPPED"

    fun parse(reason: String): Parsed {
        val match = shape.matchEntire(reason.trim()) ?: return Parsed(Outcome.UNKNOWN, detail = reason)
        val ruleId = match.groupValues[1].toLongOrNull()
        val target = match.groupValues[2].ifEmpty { null }
        val code = match.groupValues[3]
        val outcome = when {
            code == "SENT" -> Outcome.SENT
            code == "RADIO_FAILED" -> Outcome.RADIO_FAILED
            code.startsWith("FAILED") -> Outcome.FAILED
            code.startsWith("ERROR_") -> Outcome.ERROR
            else -> runCatching { Outcome.valueOf(code) }.getOrNull()?.takeIf { it != Outcome.UNKNOWN } ?: Outcome.UNKNOWN
        }
        val detail = when (outcome) {
            Outcome.ERROR -> code.removePrefix("ERROR_")
            Outcome.UNKNOWN -> reason
            else -> ""
        }
        return Parsed(outcome, ruleId, target, detail)
    }

    /** The sentence for [reason], e.g. "Not forwarded: it was already a forwarded message". */
    fun sentence(resources: Resources, reason: String): String {
        val p = parse(reason)
        val to = p.target ?: "?"
        return when (p.outcome) {
            Outcome.SENT -> resources.getString(R.string.declog_fwd_sent, to)
            Outcome.FAILED -> resources.getString(R.string.declog_fwd_failed, to)
            Outcome.RADIO_FAILED -> resources.getString(R.string.declog_fwd_radio_failed, to)
            Outcome.CODE_EXCLUDED -> resources.getString(R.string.declog_fwd_code_excluded)
            Outcome.LOOP_FORWARD -> resources.getString(R.string.declog_fwd_loop_forward)
            Outcome.LOOP_SENDER -> resources.getString(R.string.declog_fwd_loop_sender, to)
            Outcome.SIM_FALLBACK -> resources.getString(R.string.declog_fwd_sim_fallback)
            Outcome.RATE_LIMITED -> resources.getString(R.string.w2c_log_fwd_rate_limited)
            Outcome.ERROR -> resources.getString(R.string.declog_fwd_error, p.detail)
            Outcome.UNKNOWN -> p.detail
        }
    }

    /**
     * "Rule: Bank alerts" from the rule's note, else its condition ("Rule: From: Number 0555…"). Null when the entry
     * names no rule; "a deleted rule" when it is gone.
     */
    fun ruleLabel(resources: Resources, reason: String, rules: List<ForwardRule>): String? {
        val id = parse(reason).ruleId ?: return null
        val rule = rules.firstOrNull { it.id == id }
        val name = when {
            rule == null -> resources.getString(R.string.declog_fwd_rule_deleted)
            rule.note.isNotBlank() -> rule.note
            rule.senderType != null -> resources.getString(R.string.forward_row_from, rule.senderPattern)
            else -> resources.getString(R.string.forward_row_text, rule.bodyPattern)
        }
        return resources.getString(R.string.declog_fwd_rule, name)
    }
}
