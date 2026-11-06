package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.thread.planSpamReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * "Block number" in the thread menu and the conversation list. It adds a uMessage block rule, never an entry on the
 * phone's own block list: Android throws away texts from numbers on that list before the app sees them, so they
 * could not be reviewed or restored. With a rule, new texts from the number go to Blocked messages instead.
 * Numbers already on the phone's list stay there; this object never reads or changes that list.
 */
object SenderBlocking {

    /** Stored as a code and shown translated (bug 4). */
    const val NOTE = RuleNotes.BLOCKED_BY_YOU

    /** What [block] changed, so Undo can put it back with [undo]. */
    data class Change(val addedRuleIds: List<Long>, val removedAllowRules: List<SpamRule>) {
        val isEmpty: Boolean get() = addedRuleIds.isEmpty() && removedAllowRules.isEmpty()
    }

    /**
     * Adds a block rule for each address that no block rule covers yet. An allow rule (left by "Not spam") for the
     * same sender is checked before block rules and would keep it in the inbox, so it is removed.
     */
    suspend fun block(repository: SpamRepository, addresses: Collection<String>): Change {
        val rules = repository.rules.first()
        val added = LinkedHashSet<Long>()
        val removed = mutableListOf<SpamRule>()
        addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { address ->
            val plan = withContext(Dispatchers.Default) { planSpamReport(rules, address) }
            plan.allowRulesToRemove.filter { allow -> removed.none { it.id == allow.id } }.forEach {
                repository.removeRule(it.id)
                removed += it
            }
            if (!plan.alreadyBlocked) {
                added += repository.addRule(
                    SpamRule(kind = ListKind.BLOCK, type = plan.type, pattern = address, note = NOTE),
                )
            }
        }
        return Change(added.toList(), removed)
    }

    /** Reverses [block]. */
    suspend fun undo(repository: SpamRepository, change: Change) {
        change.addedRuleIds.forEach { repository.removeRule(it) }
        change.removedAllowRules.forEach { repository.addRule(it.copy(id = 0)) }
    }

    /**
     * Removes every uMessage block rule naming [address] (either SIM), whether "Block number", "Report spam" or the
     * Blocked screen added it. Returns the removed rules so Undo can add them back.
     */
    suspend fun unblock(repository: SpamRepository, address: String): List<SpamRule> {
        val rules = repository.rules.first()
        val matching = withContext(Dispatchers.Default) { blockRulesFor(rules, address) }
        matching.forEach { repository.removeRule(it.id) }
        return matching
    }

    /**
     * Whether a plain new text from [address] would reach the inbox once its block rules are gone, asked of the
     * real [filter]. A non-contact is still held as an unknown sender in Smart or Strict mode, so "Unblocked" must
     * not promise the inbox then. Blocking call (contact lookup); run it off the main thread. A filter failure
     * counts as "not sure", so the answer is false.
     */
    fun reachesInbox(filter: SpamFilter, address: String, subscriptionId: Int): Boolean =
        runCatching { filter.evaluate(IncomingMessage(address = address, body = "", subscriptionId = subscriptionId)) }
            .map { it is FilterDecision.Allow }
            .getOrDefault(false)

    /**
     * "Always allow" after an unblock: an allow rule for the sender (both SIMs), the same rule "Not spam" adds.
     * Returns the new rule's id so Undo can remove it.
     */
    suspend fun allow(repository: SpamRepository, address: String): Long {
        val type = planSpamReport(emptyList(), address).type
        return repository.addRule(
            SpamRule(kind = ListKind.ALLOW, type = type, pattern = address.trim(), note = ALLOW_NOTE),
        )
    }

    const val ALLOW_NOTE = RuleNotes.ALLOWED_BY_YOU

    /** What [alwaysAllow] changed, so Undo can put it back with [undoAllow]. */
    data class AllowChange(val addedRuleIds: List<Long>, val removedBlockRules: List<SpamRule>)

    /**
     * The allow rules of "Not spam" on the Blocked screen: removes the senders' own block rules (they would be
     * dead weight under an allow rule) and adds an allow rule for each.
     */
    suspend fun alwaysAllow(repository: SpamRepository, addresses: Collection<String>): AllowChange {
        val removed = mutableListOf<SpamRule>()
        val added = mutableListOf<Long>()
        addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { address ->
            removed += unblock(repository, address)
            added += allow(repository, address)
        }
        return AllowChange(added, removed)
    }

    /** Reverses [alwaysAllow]. */
    suspend fun undoAllow(repository: SpamRepository, change: AllowChange) {
        change.addedRuleIds.forEach { repository.removeRule(it) }
        change.removedBlockRules.forEach { repository.addRule(it.copy(id = 0)) }
    }

    /**
     * Whether [address] is an alphanumeric sender id ("BANK-X"). Anyone can send a text under any such name, so
     * allowing one also lets fake texts with the same name through. "Not spam" adds no rule for such a sender.
     */
    fun isSpoofable(address: String): Boolean = planSpamReport(emptyList(), address).type == RuleType.SENDER_ID

    /** Whether a uMessage block rule names [address]. Uses phone-number matching, so call it off the main thread. */
    fun isBlocked(rules: List<SpamRule>, address: String): Boolean = blockRulesFor(rules, address).isNotEmpty()
}
