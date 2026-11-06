package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyPolicy
import com.glooboo.umessage.helpers.smartreply.SmartReplyText
import com.glooboo.umessage.spam.TextNormalizer

/**
 * A received text that passed the checks every language shares: the policy's body checks (no link, amount or long
 * number), at most one question and nothing after it, one script. [form] is what phrases are compared with: the
 * normalised text, or for a compact script that text without spaces; [size] counts its words, or its characters.
 * Pure JVM.
 */
internal class EngineReading private constructor(
    private val body: String,
    val form: String,
    val words: List<String>,
    val script: SmartReplyText.Script,
    val hasMark: Boolean,
    val hasListMark: Boolean,
) {
    val compact: Boolean get() = script.compact

    val size: Int = if (script.compact) SmartReplyText.length(form) else words.size

    /**
     * The words as typed (accents, case and Turkish letters kept), one for each of [words]; empty when the two
     * cannot be lined up, so a caller that needs the typed words then gives up.
     */
    val rawWords: List<String> by lazy(LazyThreadSafetyMode.NONE) { alignedRawWords() }

    fun has(phrase: String): Boolean = SmartReplyText.containsPhrase(form, phrase, compact)

    fun startsWith(phrase: String): Boolean {
        val end = phrase.length
        return phrase.isNotEmpty() && form.startsWith(phrase) && (compact || end == form.length || form[end] == ' ')
    }

    fun endsWith(phrase: String): Boolean {
        val start = form.length - phrase.length
        return phrase.isNotEmpty() && form.endsWith(phrase) && (compact || start == 0 || form[start - 1] == ' ')
    }

    private fun alignedRawWords(): List<String> {
        val tokens = TextNormalizer.fold(body).filter { it !in APOSTROPHES }.split(NON_WORD).filter { it.isNotEmpty() }
        val kept = tokens.filter { SmartReplyText.normalize(it).isNotEmpty() }
        return if (kept.map { SmartReplyText.normalize(it) } == words) kept else emptyList()
    }

    companion object {
        private const val QUESTION_CHARS = "?\u061F\uFF1F"
        private const val LIST_MARKS = ",\u060C\u3001\uFF0C;\u061B/"
        private const val APOSTROPHES = "'\u2019\u02BC`"
        private val MANY_DIGITS = Regex("""\d{3}""")
        private val CURRENCY_SIGN = Regex("""\p{Sc}""")
        private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")

        fun of(body: String): EngineReading? = if (passesGates(body)) build(body) else null

        private fun passesGates(body: String): Boolean = SmartReplyPolicy.bodyAllowed(body) &&
            !hasMoneyLikeNumber(body) &&
            SmartReplyText.questionGroups(body) <= 1 &&
            !hasTextAfterQuestion(body)

        /** Beyond the policy: a number of three digits or more, or any currency sign, reads as money. */
        private fun hasMoneyLikeNumber(body: String): Boolean =
            TextNormalizer.fold(body).let { MANY_DIGITS.containsMatchIn(it) || CURRENCY_SIGN.containsMatchIn(it) }

        private fun hasTextAfterQuestion(body: String): Boolean {
            val last = body.indexOfLast { it in QUESTION_CHARS }
            return last >= 0 && body.substring(last + 1).any { it.isLetterOrDigit() }
        }

        private fun build(body: String): EngineReading? {
            val normalized = SmartReplyText.normalize(body)
            val words = normalized.split(' ').filter { it.isNotEmpty() }
            val script = SmartReplyText.script(normalized)
            return if (words.isEmpty() || script == null) {
                null
            } else {
                val form = if (script.compact) SmartReplyText.compact(normalized) else normalized
                EngineReading(
                    body, form, words, script,
                    SmartReplyText.hasQuestion(body), body.any { it in LIST_MARKS },
                )
            }
        }
    }
}
