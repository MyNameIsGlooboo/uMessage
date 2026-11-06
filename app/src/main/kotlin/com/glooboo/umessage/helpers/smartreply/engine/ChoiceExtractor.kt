package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyText
import java.util.Locale

/**
 * Rule 5 for CHOICE: the two options either side of the one separator, or a verdict that there are none.
 * Question words and fillers (every
 * word a cue of the table is made of) are stripped from the outer ends, each option has at most `maxOptionWords`
 * words (none is cut short), the two differ, and the first letter is capitalised. A list with a comma, a second
 * separator ("a or b or c") or words that cannot be lined up with what was typed are rejected: three options, or an
 * option nobody can read cleanly, are better left without a chip. A separator with nothing usable on one side (French
 * "ou" inside "où es-tu") is skipped, so the text is classified as another type. Pure JVM.
 */
internal object ChoiceExtractor {

    /** What [extract] found: clean [Options], a separator that is none ([Skip]), or a text to leave unanswered. */
    sealed interface Result {
        class Options(val options: List<String>) : Result
        object Skip : Result
        object Reject : Result
    }

    fun extract(index: EngineIndex, reading: EngineReading): Result = when {
        reading.hasListMark -> Result.Reject
        reading.compact -> fromCompact(index, reading)
        else -> fromWords(index, reading)
    }

    private fun fromWords(index: EngineIndex, reading: EngineReading): Result {
        val words = reading.words
        val raw = reading.rawWords
        val opening = starterWords(index, reading)
        val spans = ChoiceText.separatorSpans(words, index.separatorWords).filter { it.first >= opening }
        return if (spans.isEmpty()) {
            Result.Skip
        } else if (spans.size == 1 && raw.size == words.size) {
            val left = ChoiceText.trimStart(words, opening, spans[0].first, index)
            val right = ChoiceText.trimEnd(words, spans[0].last + 1, words.size, index)
            options(
                raw.subList(left.first, left.last + 1).joinToString(" "),
                raw.subList(right.first, right.last + 1).joinToString(" "),
                left.count() to right.count(),
                index,
            )
        } else {
            Result.Reject
        }
    }

    /** Words of the longest starter the text opens with, so "do you want tea or coffee" starts at "tea". */
    private fun starterWords(index: EngineIndex, reading: EngineReading): Int =
        index.starters.filter(reading::startsWith).maxOfOrNull { it.split(' ').size } ?: 0

    private fun fromCompact(index: EngineIndex, reading: EngineReading): Result {
        val hits = ChoiceText.separatorHits(reading.form, index.separators)
        return if (hits.isEmpty()) {
            Result.Skip
        } else if (hits.size == 1) {
            val (position, separator) = hits[0]
            val left = ChoiceText.stripStart(reading.form.substring(0, position), index.stopPhrases)
            val right = ChoiceText.stripEnd(reading.form.substring(position + separator.length), index.stopPhrases)
            options(left, right, SmartReplyText.length(left) to SmartReplyText.length(right), index)
        } else {
            Result.Reject
        }
    }

    private fun options(left: String, right: String, sizes: Pair<Int, Int>, index: EngineIndex): Result {
        val within = 1..index.maxOptionWords
        val clean = sizes.first in within && sizes.second in within &&
            SmartReplyText.normalize(left) != SmartReplyText.normalize(right)
        val answer = listOf(left, right).any { SmartReplyText.normalize(it) in index.answerWords }
        return when {
            sizes.first == 0 || sizes.second == 0 || answer -> Result.Skip
            clean -> Result.Options(listOf(capitalised(left, index.language), capitalised(right, index.language)))
            else -> Result.Reject
        }
    }

    private fun capitalised(text: String, language: String): String =
        text.replaceFirstChar { it.titlecase(Locale.forLanguageTag(language)) }
}
