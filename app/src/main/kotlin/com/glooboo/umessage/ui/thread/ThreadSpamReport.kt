package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * What "Report spam" has to change for one sender. An allow rule (left by "Not spam") is checked before block
 * rules, so it would keep the sender out of Blocked messages and has to go; a block rule already covering both
 * SIMs means there is nothing to add.
 */
internal data class SpamReportPlan(
    val type: RuleType,
    val allowRulesToRemove: List<SpamRule>,
    val alreadyBlocked: Boolean,
)

internal fun planSpamReport(rules: List<SpamRule>, address: String): SpamReportPlan {
    val type = if (address.any { it.isLetter() }) RuleType.SENDER_ID else RuleType.NUMBER
    val matching = rules.filter { it.type == type && isSameSender(it.pattern, address, type) }
    return SpamReportPlan(
        type = type,
        allowRulesToRemove = matching.filter { it.kind == ListKind.ALLOW },
        alreadyBlocked = matching.any { it.kind == ListKind.BLOCK && it.subscriptionId == null },
    )
}

/**
 * Whether "Report spam" would change nothing for [address]: a block rule for both SIMs already covers the sender and
 * no allow rule overrides it. The thread menu hides the action then, instead of offering it only to answer
 * "Already blocked as spam". Uses phone-number matching, so call it off the main thread.
 */
internal fun isAlreadyReported(rules: List<SpamRule>, address: String): Boolean =
    planSpamReport(rules, address).let { it.alreadyBlocked && it.allowRulesToRemove.isEmpty() }

/** Whether a stored rule pattern names [address]: numbers compared as phone numbers, sender ids ignoring case. */
internal fun isSameSender(pattern: String, address: String, type: RuleType): Boolean {
    if (type == RuleType.SENDER_ID) return pattern.trim().equals(address.trim(), ignoreCase = true)
    return when (PhoneNumberUtil.getInstance().isNumberMatch(pattern, address)) {
        // SHORT_NSN_MATCH (only the tail of the number agrees) is left out: a block rule for the short number
        // "5551234" must not be reported as covering "+1 415 555 1234".
        PhoneNumberUtil.MatchType.EXACT_MATCH,
        PhoneNumberUtil.MatchType.NSN_MATCH -> true

        else -> false
    }
}
