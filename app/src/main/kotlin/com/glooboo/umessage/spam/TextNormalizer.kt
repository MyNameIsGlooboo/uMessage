package com.glooboo.umessage.spam

/**
 * One way of reading message text for keyword, regex and code matching, and for conversation search. Pure JVM.
 *
 * [fold] removes differences that do not change what a reader sees or means:
 * - Arabic yeh and kaf become the Persian letters (ي ى → ی, ك → ک), as Persian-language senders mix both;
 * - every Unicode decimal digit (Persian ۱۲۳, Arabic-Indic ١٢٣, ...) becomes the ASCII digit;
 * - zero-width characters (ZWNJ, ZWJ, ZWSP, word joiner, BOM, soft hyphen), the kashida (ـ) and Arabic
 *   diacritics (harakat, superscript alef) are dropped.
 *
 * It keeps every other character, so a folded text is never longer than the input. Case is left as it is: matching
 * ignores case for every script (Kotlin `ignoreCase`, regex `UNICODE_CASE`), and [lower] is there for callers that
 * need a lower-case key.
 *
 * [deobfuscate] goes further and is for BLOCK keywords only, where a spammer gains by hiding a word: letters written
 * one at a time ("F R E E", "f.r.e.e") are joined, and digits or symbols standing in for letters inside a word
 * ("fr33", "fr€€", "$ale") are read as those letters.
 */
object TextNormalizer {

    /** Characters that [fold] replaces with another character. */
    val REPLACEMENTS: Map<Char, Char> = buildMap {
        put('\u064A', '\u06CC') // ي Arabic yeh -> ی
        put('\u0649', '\u06CC') // ى alef maksura -> ی
        put('\u0643', '\u06A9') // ك Arabic kaf -> ک
        for (i in 0..9) {
            put('\u06F0' + i, '0' + i) // Persian digits
            put('\u0660' + i, '0' + i) // Arabic-Indic digits
        }
    }

    /** Characters that [fold] drops. */
    val REMOVED: Set<Char> = buildSet {
        add('\u200B') // zero-width space
        add('\u200C') // zero-width non-joiner
        add('\u200D') // zero-width joiner
        add('\u2060') // word joiner
        add('\uFEFF') // zero-width no-break space / BOM
        add('\u00AD') // soft hyphen
        add('\u0640') // kashida (tatweel)
        for (c in '\u064B'..'\u065F') add(c) // harakat: fathatan .. wavy hamza below
        add('\u0670') // superscript alef
    }

    fun fold(text: String): String {
        if (text.none { it in REMOVED || it in REPLACEMENTS || (it.isDigit() && it !in '0'..'9') }) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c in REMOVED) continue
            val replaced = REPLACEMENTS[c]
            when {
                replaced != null -> out.append(replaced)
                c.isDigit() && c !in '0'..'9' -> {
                    val digit = Character.digit(c, 10)
                    out.append(if (digit >= 0) '0' + digit else c)
                }

                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** [fold], then lower case for every script (Locale-independent, so a Turkish phone still gets "i"). */
    fun lower(text: String): String = fold(text).lowercase(java.util.Locale.ROOT)

    /** Look-alike digits and symbols read as letters, but only inside a word that also has real letters. */
    private val LEET: Map<Char, Char> = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g',
        '@' to 'a', '$' to 's', '\u20AC' to 'e', '!' to 'i', '|' to 'l',
    )

    /** Separators between letters written one at a time: "F R E E", "f.r.e.e", "f-r-e-e", "f_r_e_e". */
    private val SPACED_OUT = Regex("(?<![\\p{L}\\p{N}])\\p{L}(?:[ .\\-_*]{1,2}\\p{L}){2,}(?![\\p{L}\\p{N}])")
    private val SPACED_SEPARATOR = Regex("[ .\\-_*]")

    /** A run of letters, digits and leet symbols, at least one of them a letter and one a stand-in. */
    private val WORD = Regex("[\\p{L}\\p{N}@$€!|]+")

    /**
     * [lower], then spaced-out letters joined and leetspeak inside words read as letters. A word made only of digits
     * ("2025", "50") or only of letters is left alone, so prices, codes and plain words are not changed.
     */
    fun deobfuscate(text: String): String {
        val joined = SPACED_OUT.replace(lower(text)) { it.value.replace(SPACED_SEPARATOR, "") }
        return WORD.replace(joined) { match ->
            val word = match.value
            val hasLetter = word.any { it.isLetter() }
            val hasStandIn = word.any { it in LEET }
            if (!hasLetter || !hasStandIn) {
                word
            } else {
                // "!" or "|" closing a word is punctuation ("free!"), not a letter.
                val body = word.trimEnd('!', '|')
                body.map { LEET[it] ?: it }.joinToString("") + word.substring(body.length)
            }
        }
    }

    /** Whether [text] contains [keyword] once both are folded, ignoring case in every script. */
    fun containsFolded(text: String, keyword: String): Boolean {
        val wanted = fold(keyword)
        return wanted.isNotEmpty() && fold(text).contains(wanted, ignoreCase = true)
    }

    /**
     * [containsFolded], or the same after [deobfuscate] of both sides. For BLOCK keywords: "F R E E" and "fr33" match
     * the keyword "free". The keyword is deobfuscated too, so a keyword such as "fr33" still matches itself.
     */
    fun containsObfuscated(text: String, keyword: String): Boolean {
        if (containsFolded(text, keyword)) return true
        val wanted = deobfuscate(keyword).trim()
        return wanted.isNotEmpty() && deobfuscate(text).contains(wanted)
    }

    /**
     * SQLite query for the rows of [table] whose [column], read through [fold], is LIKE the one `?` argument (pass a
     * folded [likeContains] pattern). Digits other than Persian and Arabic-Indic are left as they are; those two cover the app's users.
     *
     * SQLite's parser overflows on a few dozen nested function calls, so the REPLACE calls are spread over a chain of
     * sub-queries, [SQL_REPLACES_PER_LEVEL] per level; SQLite flattens the chain into one scan.
     */
    fun sqlFoldedLike(table: String, key: String, column: String): String {
        val steps = REPLACEMENTS.map { (from, to) -> "char(${from.code})" to "char(${to.code})" } +
            REMOVED.map { "char(${it.code})" to "''" }
        var source = "SELECT $key AS k, $column AS v FROM $table"
        for (chunk in steps.chunked(SQL_REPLACES_PER_LEVEL)) {
            var expr = "v"
            for ((from, to) in chunk) expr = "REPLACE($expr, $from, $to)"
            source = "SELECT k, $expr AS v FROM ($source)"
        }
        return "SELECT * FROM $table WHERE $key IN (SELECT k FROM ($source) WHERE v LIKE ? ESCAPE '$LIKE_ESCAPE')"
    }

    /** The escape character of every search LIKE pattern: `LIKE ? ESCAPE '\'`. */
    const val LIKE_ESCAPE = '\\'

    /**
     * A LIKE pattern matching any text that contains [text] as typed (MC-14): `%`, `_` and the escape character itself
     * are escaped, so "50%" finds only "50%" and not every message with a 50 in it. Use with `ESCAPE '\'`.
     */
    fun likeContains(text: String): String {
        val out = StringBuilder(text.length + 2).append('%')
        for (c in text) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) out.append(LIKE_ESCAPE)
            out.append(c)
        }
        return out.append('%').toString()
    }

    private const val SQL_REPLACES_PER_LEVEL = 8

    /** Whether a search for [query] needs [sqlFold]: plain ASCII words without digits match the same either way. */
    fun needsFold(query: String): Boolean = query.any { it.code > 0x7F || it.isDigit() }
}
