package com.glooboo.umessage.ui.settings.search

/**
 * Finds settings for what the owner typed. Every typed word must match somewhere in a setting (title, keywords,
 * summary or page name), as the start of a word, or, for words of 5+ letters, with one typo. A title match ranks above
 * a keyword (synonym) match, which ranks above the summary, which ranks above the page name.
 */
class SettingsSearchMatcher(docs: List<SettingsSearchDoc>) {
    private class Indexed(
        val doc: SettingsSearchDoc,
        val fields: List<List<String>>,
    )

    private val indexed = docs.map { doc ->
        Indexed(doc, FIELD_WEIGHTS.indices.map { SearchText.tokens(fieldText(doc, it)) })
    }

    /** The best [limit] settings for [query], best first; empty for a blank or one-letter query. */
    fun search(query: String, limit: Int = MAX_RESULTS): List<SettingsSearchDoc> {
        val words = SearchText.tokens(query)
        if (words.isEmpty() || words.sumOf { it.length } < MIN_QUERY_LETTERS) return emptyList()
        return indexed
            .mapNotNull { item -> score(words, item)?.let { item to it } }
            .sortedWith(compareByDescending<Pair<Indexed, Int>> { it.second }.thenBy { it.first.doc.id })
            .take(limit)
            .map { it.first.doc }
    }

    private fun score(words: List<String>, item: Indexed): Int? {
        var total = 0
        for (word in words) {
            val best = bestFor(word, item)
            if (best == 0) return null
            total += best
        }
        // Every word found in the title: a clear first pick.
        val allInTitle = words.all { word -> item.fields[TITLE].any { it.startsWith(word) } }
        return if (allInTitle) total + TITLE_BONUS else total
    }

    private fun bestFor(word: String, item: Indexed): Int {
        var best = 0
        for (field in FIELD_WEIGHTS.indices) {
            val quality = item.fields[field].maxOfOrNull { quality(word, it) } ?: 0
            best = maxOf(best, FIELD_WEIGHTS[field] * quality / QUALITY_SCALE)
        }
        return best
    }

    /** 0 for no match, else how good: exact word, start of a word, or one typo. */
    private fun quality(word: String, candidate: String): Int = when {
        candidate == word -> QUALITY_EXACT
        candidate.startsWith(word) -> QUALITY_PREFIX
        word.length >= TYPO_MIN_LETTERS && isTypoOf(word, candidate) -> QUALITY_TYPO
        else -> 0
    }

    private fun isTypoOf(word: String, candidate: String): Boolean =
        SearchText.editDistance(word, candidate) <= 1 ||
            SearchText.editDistance(word, candidate.take(word.length)) <= 1

    private fun fieldText(doc: SettingsSearchDoc, field: Int): String = when (field) {
        TITLE -> doc.title
        KEYWORDS -> doc.keywords
        SUMMARY -> doc.summary
        else -> doc.page
    }

    companion object {
        const val MAX_RESULTS = 8
        private const val MIN_QUERY_LETTERS = 2
        private const val TYPO_MIN_LETTERS = 5
        private const val TITLE = 0
        private const val KEYWORDS = 1
        private const val SUMMARY = 2
        private const val TITLE_BONUS = 50

        /** Title, keywords, summary, page name. */
        private val FIELD_WEIGHTS = intArrayOf(100, 60, 30, 15)
        private const val QUALITY_SCALE = 100
        private const val QUALITY_EXACT = 100
        private const val QUALITY_PREFIX = 90
        private const val QUALITY_TYPO = 55
    }
}
