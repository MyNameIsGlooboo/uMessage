package com.glooboo.umessage.spam

import android.content.res.Resources
import com.glooboo.umessage.R

/**
 * SE-39: names the rule behind a decision-log entry. The log stores the rule's id and a short label (its value, never
 * message text); this turns an entry into "Blocked by rule: <label>".
 *
 * Call from the log screen: `DecisionRuleText.blockedByRule(resources, entry, rules)`, where [rules] is the current
 * rule list (may be empty); null means the entry was not decided by a block rule, so show the usual reason text.
 */
object DecisionRuleText {
    const val MAX_LABEL = 24

    /** The rule's value, whitespace folded and cut to [MAX_LABEL] characters. */
    fun label(rule: SpamRule): String {
        val text = rule.pattern.trim().replace(Regex("\\s+"), " ")
        return if (text.length <= MAX_LABEL) text else text.take(MAX_LABEL - 1) + "…"
    }

    /**
     * The label to show for [entry]: the rule's current value when it still exists, else the label stored with the
     * entry, else "#id". Null when the entry is not a BLOCK or an ALLOW by a stored rule.
     */
    fun ruleLabel(entry: SpamLogEntry, rules: List<SpamRule> = emptyList()): String? {
        if (!isBlockRule(entry) && !isAllowRule(entry)) return null
        val id = entry.ruleId ?: return null
        return rules.firstOrNull { it.id == id }?.let(::label)?.takeIf { it.isNotEmpty() }
            ?: entry.ruleLabel.takeIf { it.isNotBlank() }
            ?: "#$id"
    }

    /**
     * "Blocked by rule: <label>"; "Blocked by the old block list" for a BLOCK_RULE entry without a rule id (the Fossify
     * lists); null for every other entry.
     */
    fun blockedByRule(
        resources: Resources,
        entry: SpamLogEntry,
        rules: List<SpamRule> = emptyList(),
        display: (String) -> String = { it },
    ): String? {
        if (entry.decision != "BLOCK" || entry.reason != DecisionReason.BLOCK_RULE.name) return null
        val label = ruleLabel(entry, rules) ?: return resources.getString(R.string.w2c_log_blocked_by_old_list)
        return resources.getString(R.string.w2c_log_blocked_by_rule, display(label))
    }

    /**
     * "Allowed by rule: <label>" for an ALLOW_RULE entry that names its rule; null for every other entry (older
     * entries, written before allow decisions kept their rule, keep the plain "Allow rule" reason).
     */
    fun allowedByRule(
        resources: Resources,
        entry: SpamLogEntry,
        rules: List<SpamRule> = emptyList(),
        display: (String) -> String = { it },
    ): String? {
        if (!isAllowRule(entry)) return null
        val label = ruleLabel(entry, rules) ?: return null
        return resources.getString(R.string.declog_allowed_by_rule, display(label))
    }

    /** The rule sentence for either side, or null. [display] formats the label (a number rule shows grouped). */
    fun byRule(
        resources: Resources,
        entry: SpamLogEntry,
        rules: List<SpamRule> = emptyList(),
        display: (String) -> String = { it },
    ): String? =
        blockedByRule(resources, entry, rules, display) ?: allowedByRule(resources, entry, rules, display)

    private fun isBlockRule(entry: SpamLogEntry) =
        entry.decision == "BLOCK" && entry.reason == DecisionReason.BLOCK_RULE.name

    private fun isAllowRule(entry: SpamLogEntry) =
        entry.decision == "ALLOW" && entry.reason == DecisionReason.ALLOW_RULE.name
}
