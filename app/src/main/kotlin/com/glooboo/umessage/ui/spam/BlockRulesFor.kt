package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.thread.isSameSender
import com.glooboo.umessage.ui.thread.planSpamReport

/** The block rules of [rules] that name [address]. Uses phone-number matching, so call it off the main thread. */
internal fun blockRulesFor(rules: List<SpamRule>, address: String): List<SpamRule> {
    val type = planSpamReport(emptyList(), address).type
    return rules.filter { it.kind == ListKind.BLOCK && it.type == type && isSameSender(it.pattern, address, type) }
}
