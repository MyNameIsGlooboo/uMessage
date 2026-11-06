package com.glooboo.umessage.helpers.smartreply.engine

/** Word and phrase scanning helpers of [ChoiceExtractor]. Pure JVM. */
internal object ChoiceText {

    /** Where the separators stand in [words], the longest first, none inside another. */
    fun separatorSpans(words: List<String>, separators: List<List<String>>): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < words.size) {
            val match = separators.firstOrNull { it.isNotEmpty() && startsAt(words, i, it) }
            i += match?.size ?: 1
            if (match != null) spans.add(i - match.size until i)
        }
        return spans
    }

    private fun startsAt(words: List<String>, at: Int, phrase: List<String>): Boolean =
        at + phrase.size <= words.size && phrase.indices.all { words[at + it] == phrase[it] }

    /** Where the separators stand in a compact text: (position, separator). */
    fun separatorHits(form: String, separators: List<String>): List<Pair<Int, String>> {
        val hits = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < form.length) {
            val match = separators.firstOrNull { it.isNotEmpty() && form.startsWith(it, i) }
            if (match != null) hits.add(i to match)
            i += match?.length ?: 1
        }
        return hits
    }

    /** [from] moved on past leading question words and filler cues, never beyond [to]. */
    fun trimStart(words: List<String>, from: Int, to: Int, index: EngineIndex): IntRange {
        var start = minOf(from, to)
        var step = leadingStep(words, start, to, index)
        while (step > 0) {
            start += step
            step = leadingStep(words, start, to, index)
        }
        return start until to
    }

    /** [to] moved back past trailing question words and filler cues, never before [from]. */
    fun trimEnd(words: List<String>, from: Int, to: Int, index: EngineIndex): IntRange {
        var end = to
        var step = trailingStep(words, from, end, index)
        while (step > 0) {
            end -= step
            step = trailingStep(words, from, end, index)
        }
        return from until end
    }

    private fun leadingStep(words: List<String>, at: Int, to: Int, index: EngineIndex): Int {
        val cue = index.stopSequences.firstOrNull { at + it.size <= to && startsAt(words, at, it) }
        return when {
            at >= to -> 0
            cue != null -> cue.size
            words[at] in index.stopWords -> 1
            else -> 0
        }
    }

    private fun trailingStep(words: List<String>, from: Int, end: Int, index: EngineIndex): Int {
        val cue = index.stopSequences.firstOrNull { end - it.size >= from && endsAt(words, end, it) }
        return when {
            end <= from -> 0
            cue != null -> cue.size
            words[end - 1] in index.stopWords -> 1
            else -> 0
        }
    }

    private fun endsAt(words: List<String>, end: Int, phrase: List<String>): Boolean =
        end - phrase.size >= 0 && startsAt(words, end - phrase.size, phrase)

    fun stripStart(text: String, stop: List<String>): String {
        var rest = text
        var next = stop.firstOrNull { rest.startsWith(it) }
        while (next != null) {
            rest = rest.removePrefix(next)
            next = stop.firstOrNull { rest.startsWith(it) }
        }
        return rest
    }

    fun stripEnd(text: String, stop: List<String>): String {
        var rest = text
        var next = stop.firstOrNull { rest.endsWith(it) }
        while (next != null) {
            rest = rest.removeSuffix(next)
            next = stop.firstOrNull { rest.endsWith(it) }
        }
        return rest
    }
}
