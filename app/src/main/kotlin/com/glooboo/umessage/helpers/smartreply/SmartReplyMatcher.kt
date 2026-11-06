package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.helpers.smartreply.engine.SmartEngine
import java.util.Locale

/** What to offer for one received text: up to three [replies] in the text's own language. */
data class SmartReply(
    val intentId: String,
    val language: String,
    val replies: List<String>,
    val confidence: Double,
    val rightToLeft: Boolean,
)

/**
 * Reads one received text and says which replies fit, or null when it is not sure. A wrong chip is worse than none,
 * so every doubt ends in null: a text with a link, an amount or a long number, a long text, a text in two scripts or
 * languages, one with two questions, one with a veto word, or one that fits two kinds of message equally well.
 *
 * Per table: the text must equal a phrase of an intent ([EXACT_CONFIDENCE], after greeting and address words are
 * dropped) or hold one of its `contains` phrases ([CONTAINS_CONFIDENCE]); the best intent must be alone at the top
 * and clear of the runner-up by [MIN_MARGIN]. Pure JVM; the tables come from [SmartReplyTables].
 *
 * When this layer finds nothing (and no veto word is present), the optional [engine] reads what a question asks.
 */
class SmartReplyMatcher(tables: List<SmartReplyTable>, private val engine: SmartEngine? = null) {

    private val indexes = tables.map(::SmartReplyIndex)

    /**
     * [preferredLanguage] (the app language, as Android names it: "in" is Indonesian, "zh-CN" is Chinese) settles a
     * text that two tables whose scripts both read it also read (Russian and Ukrainian, Persian and Arabic).
     * An intent may list up to eight reply variants; [seed] (the message id) picks three of them the same way every
     * time. Seed 0 gives the first three in file order.
     */
    fun match(body: String, preferredLanguage: String? = null, seed: Long = 0L): SmartReply? =
        phraseMatch(body, preferredLanguage, seed)
            ?: engine?.takeUnless { vetoed(body) }?.reply(body, preferredLanguage, seed)

    private fun phraseMatch(body: String, preferredLanguage: String?, seed: Long): SmartReply? {
        val text = Reading.of(body) ?: return null
        val hits = indexes.filter { it.script.reads(text.script) }.mapNotNull { hitIn(it, text, seed) }
        return if (hits.size > 1) choose(hits, languageCode(preferredLanguage)) else hits.firstOrNull()?.reply
    }

    /** A veto word of any phrase table (sensitive words, money, codes) keeps the second layer quiet too. */
    private fun vetoed(body: String): Boolean {
        val normalized = SmartReplyText.normalize(body)
        val script = SmartReplyText.script(normalized) ?: return false
        val form = if (script.compact) SmartReplyText.compact(normalized) else normalized
        return indexes.any { index ->
            index.script.reads(script) && index.veto.any { SmartReplyText.containsPhrase(form, it, script.compact) }
        }
    }

    private fun choose(hits: List<TableHit>, preferredLanguage: String?): SmartReply? =
        (hits.firstOrNull { languageCode(it.reply.language) == preferredLanguage } ?: hits.firstOrNull { it.tieBreak })
            ?.reply

    private fun hitIn(index: SmartReplyIndex, text: Reading, seed: Long): TableHit? {
        if (index.veto.any { text.has(it) }) return null
        val stripped = if (text.script.compact) {
            stripCompactFillers(text.form, index.fillersLongestFirst)
        } else {
            stripFillers(text.words, index.fillers).joinToString(" ")
        }
        val hits = index.intents.mapNotNull { intentHit(index, it, text, stripped) }
        val top = hits.maxByOrNull { it.confidence } ?: return null
        val leaders = hits.filter { it.confidence == top.confidence }
        val runnerUp = hits.filter { it.intent.intent.id != top.intent.intent.id }.maxOfOrNull { it.confidence }
        val alone = leaders.all { it.intent === top.intent }
        val clear = runnerUp == null || top.confidence - runnerUp >= MIN_MARGIN
        return if (alone && clear) top.toTableHit(index, seed) else null
    }

    private fun intentHit(
        index: SmartReplyIndex,
        intent: SmartReplyIndex.IndexedIntent,
        text: Reading,
        stripped: String,
    ): IntentHit? {
        val blocked = (intent.intent.statement && text.hasQuestion) || intent.veto.any { text.has(it) }
        val short = text.size <= index.table.maxWords
        val confidence = when {
            blocked -> 0.0
            text.form in intent.exact || stripped in intent.exact -> EXACT_CONFIDENCE
            short && intent.contains.any { text.has(it) } -> CONTAINS_CONFIDENCE
            else -> 0.0
        }
        return if (confidence > 0.0 && confidence >= index.table.minConfidence) IntentHit(intent, confidence) else null
    }

    /** Leading and trailing filler words go, as long as one word is left. */
    private fun stripFillers(words: List<String>, fillers: Set<String>): List<String> {
        var from = 0
        var to = words.size
        while (to - from > 1 && words[from] in fillers) from++
        while (to - from > 1 && words[to - 1] in fillers) to--
        return words.subList(from, to)
    }

    /** Compact scripts: a filler at either end goes, as long as something is left. */
    private fun stripCompactFillers(form: String, fillers: List<String>): String {
        var text = form
        var next = stripOneFiller(text, fillers)
        while (next != null) {
            text = next
            next = stripOneFiller(text, fillers)
        }
        return text
    }

    private fun stripOneFiller(text: String, fillers: List<String>): String? = fillers.firstNotNullOfOrNull { filler ->
        when {
            text.length <= filler.length -> null
            text.startsWith(filler) -> text.removePrefix(filler)
            text.endsWith(filler) -> text.removeSuffix(filler)
            else -> null
        }
    }

    private class IntentHit(val intent: SmartReplyIndex.IndexedIntent, val confidence: Double) {
        fun toTableHit(index: SmartReplyIndex, seed: Long) = TableHit(
            SmartReply(
                intentId = intent.intent.id,
                language = index.language,
                replies = pickReplies(intent.intent.replies, seed),
                confidence = confidence,
                rightToLeft = index.script == SmartReplyText.Script.Arabic,
            ),
            index.table.tieBreak,
        )
    }

    private class TableHit(val reply: SmartReply, val tieBreak: Boolean)

    /**
     * A received text that passed the checks every language shares. [form] is what phrases are compared with: the
     * normalised text, or for a compact script that text without spaces; [size] counts its words, or its characters.
     */
    private class Reading(
        val form: String,
        val words: List<String>,
        val size: Int,
        val script: SmartReplyText.Script,
        val hasQuestion: Boolean,
    ) {
        fun has(phrase: String): Boolean = SmartReplyText.containsPhrase(form, phrase, script.compact)

        companion object {
            fun of(body: String): Reading? {
                if (!SmartReplyPolicy.bodyAllowed(body) || SmartReplyText.questionGroups(body) > 1) return null
                val normalized = SmartReplyText.normalize(body)
                val words = normalized.split(' ').filter { it.isNotEmpty() }
                val script = SmartReplyText.script(normalized)
                if (words.isEmpty() || script == null) return null
                val form = if (script.compact) SmartReplyText.compact(normalized) else normalized
                val size = if (script.compact) SmartReplyText.length(form) else words.size
                if (size > (if (script.compact) MAX_TEXT_CHARS else MAX_TEXT_WORDS)) return null
                return Reading(form, words, size, script, SmartReplyText.hasQuestion(body))
            }
        }
    }

    private companion object {
        const val EXACT_CONFIDENCE = 1.0
        const val CONTAINS_CONFIDENCE = 0.85
        const val MIN_MARGIN = 0.1
        const val MAX_TEXT_WORDS = 10
        const val MAX_TEXT_CHARS = 30
        const val MAX_REPLIES = 3

        /**
         * [MAX_REPLIES] of the intent's variants: the first ones in file order for seed 0 or a pool that fits,
         * otherwise a shuffle seeded with [seed], so one message always gets the same picks.
         */
        fun pickReplies(pool: List<String>, seed: Long): List<String> =
            if (seed == 0L || pool.size <= MAX_REPLIES) {
                pool.take(MAX_REPLIES)
            } else {
                pool.shuffled(java.util.Random(seed)).take(MAX_REPLIES)
            }

        /** Java's old ISO 639 codes, still returned by Android for Indonesian and Hebrew. */
        val LEGACY_CODES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
    }

    /** The bare language of a code as Android or a table writes it: "zh-CN" and "zh_TW" are "zh", "in" is "id". */
    private fun languageCode(code: String?): String? {
        val language = code?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        return LEGACY_CODES[language] ?: language
    }
}
