package com.glooboo.umessage.spam

import java.util.regex.Pattern

/**
 * How a KEYWORD rule's text is matched. Normally a keyword is a plain substring ("win" is found in "window"). A keyword
 * written in double quotes (`"win"`) is a whole-word keyword: it only matches as a word of its own, so it does not hit
 * "window" or "Darwin". The quotes are part of the stored pattern (no database change) and are put on and taken off by
 * the "Whole word only" switch of the rule sheet. Pure JVM.
 */
object KeywordMatch {
    private const val QUOTE = '"'
    private const val MIN_WRAPPED = 3
    private const val EDGE_BEFORE = "(?<![\\p{L}\\p{M}\\p{N}])"
    private const val EDGE_AFTER = "(?![\\p{L}\\p{M}\\p{N}])"

    /** Whether [pattern] is a whole-word keyword: at least one character between a pair of double quotes. */
    fun isWholeWord(pattern: String): Boolean {
        val p = pattern.trim()
        return p.length >= MIN_WRAPPED && p.first() == QUOTE && p.last() == QUOTE
    }

    /** The keyword itself: [pattern] without the quotes of a whole-word keyword, trimmed. */
    fun bare(pattern: String): String {
        val p = pattern.trim()
        return if (isWholeWord(p)) p.substring(1, p.length - 1).trim() else p
    }

    /** [pattern] as a whole-word keyword (quoted), or, when it already is one, as a plain keyword. */
    fun toggled(pattern: String): String {
        val word = bare(pattern)
        return if (isWholeWord(pattern)) word else if (word.isEmpty()) pattern else "$QUOTE$word$QUOTE"
    }

    /**
     * Whether [text] holds the keyword [pattern] (plain or whole-word). With [obfuscated] a block keyword also sees
     * through spaced-out letters and leetspeak, as [TextNormalizer.containsObfuscated] does.
     */
    fun matches(text: String, pattern: String, obfuscated: Boolean): Boolean {
        if (!isWholeWord(pattern)) {
            return if (obfuscated) {
                TextNormalizer.containsObfuscated(text, pattern)
            } else {
                TextNormalizer.containsFolded(text, pattern)
            }
        }
        val word = bare(pattern)
        if (word.isEmpty()) return false
        if (hasWord(TextNormalizer.fold(text), TextNormalizer.fold(word))) return true
        if (!obfuscated) return false
        val wanted = TextNormalizer.deobfuscate(word).trim()
        return wanted.isNotEmpty() && hasWord(TextNormalizer.deobfuscate(text), wanted)
    }

    private fun hasWord(text: String, word: String): Boolean = Pattern
        .compile(EDGE_BEFORE + Pattern.quote(word) + EDGE_AFTER, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        .matcher(text)
        .find()
}
