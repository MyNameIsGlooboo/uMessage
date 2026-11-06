package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule

/**
 * Pure conflict checks over the rules held in memory (ID-42). The filter checks allow rules before block rules, so a
 * block rule that an allow rule covers never applies.
 */
object RuleConflicts {

    private val senderTypes = setOf(RuleType.NUMBER, RuleType.PREFIX, RuleType.SENDER_ID)

    /**
     * The allow rule that always wins over the block rule [block], or null. Only sender rules (number, prefix, sender
     * name) can be judged without a message; keyword and pattern rules depend on the text.
     */
    fun overriddenBy(rules: List<SpamRule>, block: SpamRule): SpamRule? {
        if (block.kind != ListKind.BLOCK || block.type !in senderTypes) return null
        return RuleValidator.conflictingAllows(rules, block.type, block.pattern, block.subscriptionId).firstOrNull()
    }

    /** The ids of the block rules in [rules] that an allow rule covers. */
    fun overriddenBlockIds(rules: List<SpamRule>): Set<Long> =
        rules.filter { it.kind == ListKind.BLOCK && overriddenBy(rules, it) != null }.map { it.id }.toSet()
}
