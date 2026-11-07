package com.glooboo.umessage.spam.engine

/**
 * Whether a number was written with an explicit "+" (international form). Text from right-to-left languages puts
 * invisible direction marks in front of numbers, "(+44) 7..." opens with a bracket, and some keyboards type a
 * full-width plus (U+FF0B); none of those may hide the plus.
 */
internal object PlusPrefix {
    private const val FULLWIDTH_PLUS = '\uFF0B'
    private val LRM_RLM = '\u200E'..'\u200F'
    private val EMBEDDINGS = '\u202A'..'\u202E'
    private val ISOLATES = '\u2066'..'\u2069'

    /** Left-to-right and right-to-left marks, embeddings, overrides and isolates. */
    private fun isDirectionMark(c: Char) = c in LRM_RLM || c in EMBEDDINGS || c in ISOLATES

    /** True when [value], after spaces, direction marks and opening brackets, starts with a plus. */
    fun startsWithPlus(value: String): Boolean {
        val head = value.trimStart { it.isWhitespace() || it == '(' || isDirectionMark(it) }
        return head.startsWith("+") || head.startsWith(FULLWIDTH_PLUS)
    }
}
