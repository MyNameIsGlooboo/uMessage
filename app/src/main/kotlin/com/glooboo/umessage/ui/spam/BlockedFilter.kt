package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.ui.components.asciiDigits

/**
 * The chip row under the Blocked banner (ID-14): narrows the held messages by why or where they were held. Uses only
 * what each blocked row already carries (reason, text, SIM); nothing is stored per message.
 */
sealed interface BlockedFilter {
    data object All : BlockedFilter

    /** Held because the sender is not a contact or a known number. */
    data object UnknownSender : BlockedFilter

    /** Held by one of the user's own block rules (or an old block list). */
    data object MyRules : BlockedFilter

    /** The text contains a web address. */
    data object HasLink : BlockedFilter

    data class Sim(val subscriptionId: Int) : BlockedFilter
}

object BlockedFilters {

    fun matches(filter: BlockedFilter, message: BlockedMessage): Boolean = when (filter) {
        BlockedFilter.All -> true
        BlockedFilter.UnknownSender ->
            message.reason == DecisionReason.UNKNOWN_SENDER || message.reason == DecisionReason.FOREIGN_NUMBER
        BlockedFilter.MyRules -> message.reason == DecisionReason.BLOCK_RULE
        BlockedFilter.HasLink -> BlockedHints.hasLink(message.body)
        is BlockedFilter.Sim -> message.subscriptionId == filter.subscriptionId
    }

    fun apply(filter: BlockedFilter, messages: List<BlockedMessage>): List<BlockedMessage> =
        if (filter == BlockedFilter.All) messages else messages.filter { matches(filter, it) }

    /** The digits a number query needs before it is compared with sender numbers. */
    private const val MIN_NUMBER_DIGITS = 2

    /**
     * Whether [message] answers the search [query] (spamui-20): its text or its sender holds the typed words. Letter
     * forms, digits and zero-width marks are folded on both sides like the main search ([TextNormalizer.fold]); a query
     * of digits and number marks also finds the sender in any written form ("0555 345" finds "+90555345..."). A blank
     * query matches everything.
     */
    fun matchesQuery(message: BlockedMessage, query: String): Boolean {
        val typed = query.trim()
        if (typed.isEmpty()) return true
        val wanted = TextNormalizer.fold(typed)
        if (TextNormalizer.fold(message.body).contains(wanted, ignoreCase = true)) return true
        if (TextNormalizer.fold(message.address).contains(wanted, ignoreCase = true)) return true
        val numberQuery = typed.all { it.isDigit() || it in " +-()" }
        val digits = asciiDigits(typed)
        return numberQuery && digits.length >= MIN_NUMBER_DIGITS && asciiDigits(message.address).contains(digits)
    }

    /**
     * The sender rows of [groups] that answer [query]: a sender stays when any of its messages does, and keeps all of
     * its messages, so an action on the row (Not spam, Delete) still acts on the whole sender.
     */
    fun searchGroups(groups: List<SenderGroup>, query: String): List<SenderGroup> =
        if (query.isBlank()) groups else groups.filter { g -> g.messages.any { matchesQuery(it, query) } }

    /**
     * How many senders each chip would list, for the count badges. The list shows one row per sender, so counting
     * messages made "All 4" sit over 3 rows when one sender had two messages (emulator check, round 4). A sender
     * counts for a chip when any of their messages matches it, as [apply] then [groupBySender] would list them.
     */
    fun counts(
        messages: List<BlockedMessage>,
        subscriptionIds: Collection<Int>,
        keyOf: (String) -> String = { it },
    ): Map<BlockedFilter, Int> {
        // One pass: the link check (two regexes) runs once per message, not once per chip.
        val all = HashSet<String>()
        val unknown = HashSet<String>()
        val rules = HashSet<String>()
        val links = HashSet<String>()
        val bySim = subscriptionIds.associateWith { HashSet<String>() }
        for (message in messages) {
            val key = keyOf(message.address)
            all += key
            if (matches(BlockedFilter.UnknownSender, message)) unknown += key
            if (matches(BlockedFilter.MyRules, message)) rules += key
            if (BlockedHints.hasLink(message.body)) links += key
            bySim[message.subscriptionId]?.add(key)
        }
        return buildMap {
            put(BlockedFilter.All, all.size)
            put(BlockedFilter.UnknownSender, unknown.size)
            put(BlockedFilter.MyRules, rules.size)
            put(BlockedFilter.HasLink, links.size)
            bySim.forEach { (id, keys) -> put(BlockedFilter.Sim(id), keys.size) }
        }
    }

    /**
     * Whether a chip is worth showing (U31): All and the chosen chip always; any other only when it narrows the list,
     * so "Unknown sender 4" next to "All 4" is left out.
     */
    fun chipShown(option: BlockedFilter, selected: BlockedFilter, counts: Map<BlockedFilter, Int>): Boolean {
        if (option == BlockedFilter.All || option == selected) return true
        val count = counts[option] ?: 0
        return count > 0 && count < (counts[BlockedFilter.All] ?: 0)
    }

    /** The chip row appears only when some chip other than All can be shown, or one is still chosen. */
    fun rowShown(selected: BlockedFilter, counts: Map<BlockedFilter, Int>): Boolean =
        selected != BlockedFilter.All || counts.keys.any { it != BlockedFilter.All && chipShown(it, selected, counts) }

    /** Stored form of the selection, so it survives leaving the screen. */
    fun encode(filter: BlockedFilter): String = when (filter) {
        BlockedFilter.All -> "all"
        BlockedFilter.UnknownSender -> "unknown"
        BlockedFilter.MyRules -> "rules"
        BlockedFilter.HasLink -> "link"
        is BlockedFilter.Sim -> "sim:${filter.subscriptionId}"
    }

    /** Anything unreadable means [BlockedFilter.All]. */
    fun decode(value: String?): BlockedFilter = when {
        value == "unknown" -> BlockedFilter.UnknownSender
        value == "rules" -> BlockedFilter.MyRules
        value == "link" -> BlockedFilter.HasLink
        value != null && value.startsWith("sim:") ->
            value.removePrefix("sim:").toIntOrNull()?.let { BlockedFilter.Sim(it) } ?: BlockedFilter.All
        else -> BlockedFilter.All
    }
}
