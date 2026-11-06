package com.glooboo.umessage.helpers.smartreply

/**
 * A [SmartReplyTable] with every phrase already normalised, built once when the tables are loaded. For a compact
 * script (see [SmartReplyText]) the phrases are also stripped of their spaces.
 */
internal class SmartReplyIndex(val table: SmartReplyTable) {

    val language: String get() = table.language

    val script: SmartReplyText.Script = SmartReplyText.Script.named(table.script)
    private val compact = script.compact

    val fillers: Set<String> = normalized(table.fillers, compact).toSet()

    /** The same fillers, longest first, for stripping them off the ends of a compact text. */
    val fillersLongestFirst: List<String> = fillers.sortedByDescending { it.length }
    val veto: List<String> = normalized(table.veto, compact)
    val intents: List<IndexedIntent> = table.intents.map { IndexedIntent(it, compact) }

    class IndexedIntent(val intent: SmartReplyIntent, compact: Boolean) {
        val exact: Set<String> = normalized(intent.exact, compact).toSet()
        val contains: List<String> = normalized(intent.contains, compact)
        val veto: List<String> = normalized(intent.veto, compact)
    }
}

private fun normalized(phrases: List<String>, compact: Boolean): List<String> =
    phrases.map { SmartReplyText.normalize(it).let { n -> if (compact) SmartReplyText.compact(n) else n } }
        .filter { it.isNotEmpty() }
