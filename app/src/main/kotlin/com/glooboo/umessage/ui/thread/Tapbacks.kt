package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.Message

/** The six reactions an iPhone sends to an Android phone as a text such as `Liked “See you at 8”`. */
enum class TapbackKind(val emoji: String, val verb: String) {
    Liked("\uD83D\uDC4D", "Liked"),
    Loved("\u2764\uFE0F", "Loved"),
    Disliked("\uD83D\uDC4E", "Disliked"),
    Laughed("\uD83D\uDE02", "Laughed at"),
    Emphasized("\u203C\uFE0F", "Emphasized"),
    Questioned("\u2753", "Questioned"),
}

/** [quoted] is the text between the quotes; [truncated] when iOS cut a long original short with "…". */
data class Tapback(val kind: TapbackKind, val quoted: String, val truncated: Boolean)

/** One kind of reaction on a message: how many were sent and the original texts, for the "show original" sheet. */
data class TapbackReaction(val kind: TapbackKind, val count: Int, val originals: List<String>)

/** What [applyTapbacks] found: the reaction texts to leave out of the list, and the reactions of each message. */
data class TapbackResult(
    val hidden: Set<Long>,
    val reactions: Map<Long, List<TapbackReaction>>,
) {
    companion object {
        val None = TapbackResult(emptySet(), emptyMap())
    }
}

/** An opening quote, at least one character and a closing quote. */
private const val MIN_QUOTED_LENGTH = 3
private val OPENING_QUOTES = charArrayOf('\u201C', '"')
private val CLOSING_QUOTES = charArrayOf('\u201D', '"')
private val ELLIPSES = listOf("\u2026", "...")

/**
 * The reaction in [body], or null. Only the exact iOS wording is accepted: the verb, one space, then the original in
 * quotes and nothing else. "Liked an image" (no quoted text) and every other text are not reactions.
 * The Persian iOS wording is not listed yet: add its verbs to [TapbackKind] when a source is checked.
 */
fun parseTapback(body: String): Tapback? {
    val text = body.trim()
    for (kind in TapbackKind.entries) {
        val prefix = kind.verb + " "
        if (!text.startsWith(prefix)) continue
        val rest = text.substring(prefix.length)
        val quotedOk = rest.length >= MIN_QUOTED_LENGTH &&
            rest.first() in OPENING_QUOTES &&
            rest.last() in CLOSING_QUOTES
        if (!quotedOk) return null
        var quoted = rest.substring(1, rest.length - 1)
        val ellipsis = ELLIPSES.firstOrNull { quoted.endsWith(it) }
        if (ellipsis != null) quoted = quoted.removeSuffix(ellipsis)
        if (quoted.isBlank()) return null
        return Tapback(kind, quoted, truncated = ellipsis != null)
    }
    return null
}

private fun folded(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * The message among [candidates] (oldest first, all before the reaction) that [tapback] names: the newest exact match,
 * else the newest whose text starts with the quote (an ellipsized original), else the same two with case and spacing
 * folded. Null when none does.
 */
fun findTapbackTarget(tapback: Tapback, candidates: List<Message>): Message? {
    val quoted = tapback.quoted
    val foldedQuote = folded(quoted)
    return candidates.lastOrNull { it.body == quoted }
        ?: candidates.lastOrNull { tapback.truncated && it.body.startsWith(quoted) }
        ?: candidates.lastOrNull { folded(it.body) == foldedQuote }
        ?: candidates.lastOrNull { tapback.truncated && folded(it.body).startsWith(foldedQuote) }
}

/**
 * I30: finds the received reaction texts in [sorted] (oldest first) whose target is an earlier message of the thread.
 * Those texts are returned in [TapbackResult.hidden] and counted on the target in [TapbackResult.reactions]. A reaction
 * without a target (a photo, an older message that is not loaded, a message that was deleted) is left alone.
 */
fun applyTapbacks(sorted: List<Message>): TapbackResult {
    val hidden = HashSet<Long>()
    val found = LinkedHashMap<Long, LinkedHashMap<TapbackKind, MutableList<String>>>()
    sorted.forEachIndexed { index, message ->
        if (!message.isReceivedMessage() || message.attachment?.attachments?.isNotEmpty() == true) return@forEachIndexed
        val tapback = parseTapback(message.body) ?: return@forEachIndexed
        val candidates = sorted.subList(0, index).filter { it.body.isNotEmpty() && it.getStableId() !in hidden }
        val target = findTapbackTarget(tapback, candidates) ?: return@forEachIndexed
        hidden.add(message.getStableId())
        found.getOrPut(target.getStableId()) { LinkedHashMap() }
            .getOrPut(tapback.kind) { ArrayList() }
            .add(message.body.trim())
    }
    if (hidden.isEmpty()) return TapbackResult.None
    return TapbackResult(
        hidden,
        found.mapValues { (_, kinds) ->
            kinds.map { (kind, originals) -> TapbackReaction(kind, originals.size, originals) }
        },
    )
}
