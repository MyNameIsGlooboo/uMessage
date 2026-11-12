package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import kotlin.math.ceil

/** Longest rule text shown in a reason chip; longer patterns are cut with an ellipsis. */
private const val MAX_RULE_TEXT = 32

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Why [message] was blocked, as the chip on the Blocked screen shows it (SU-22): the rule's own text for a rule
 * block ("Rule: keyword "free""), otherwise the reason. Null for reasons that have no rule text; the caller then
 * shows the plain reason chip.
 */
fun ruleReasonText(resources: Resources, message: BlockedMessage, rules: Map<Long, SpamRule>): String? {
    if (message.reason != DecisionReason.BLOCK_RULE) return null
    val id = message.ruleId ?: return null
    val rule = rules[id] ?: return resources.getString(R.string.spam_reason_rule_removed)
    val text = rule.pattern.trim().let { if (it.length > MAX_RULE_TEXT) it.take(MAX_RULE_TEXT - 1) + "…" else it }
    val res = when (rule.type) {
        RuleType.KEYWORD -> R.string.spam_reason_rule_keyword
        RuleType.REGEX -> R.string.spam_reason_rule_regex
        RuleType.NUMBER -> R.string.spam_reason_rule_number
        RuleType.PREFIX -> R.string.spam_reason_rule_prefix
        RuleType.SENDER_ID -> R.string.spam_reason_rule_sender
        RuleType.DOMAIN -> R.string.spam_reason_rule_domain
    }
    return resources.getString(res, text)
}

/**
 * Whole days until the auto-delete removes a message received at [receivedAt] (SU-23), or null when blocked
 * messages are kept ([purgeAfterDays] 0). 0 means it goes the next time the purge runs. Matches
 * [com.glooboo.umessage.spam.SpamRepository.purgeExpired], which deletes messages older than [purgeAfterDays].
 */
fun daysUntilPurge(receivedAt: Long, now: Long, purgeAfterDays: Int): Int? {
    if (purgeAfterDays <= 0) return null
    val left = receivedAt + purgeAfterDays * DAY_MS - now
    return if (left <= 0) 0 else ceil(left.toDouble() / DAY_MS).toInt()
}

/** The per-message auto-delete label, or null when nothing is auto-deleted. */
fun purgeLeftText(resources: Resources, receivedAt: Long, now: Long, purgeAfterDays: Int): String? {
    val days = daysUntilPurge(receivedAt, now, purgeAfterDays) ?: return null
    return if (days == 0) {
        resources.getString(R.string.spam_purge_left_today)
    } else {
        resources.getQuantityString(R.plurals.spam_purge_left, days, days)
    }
}
