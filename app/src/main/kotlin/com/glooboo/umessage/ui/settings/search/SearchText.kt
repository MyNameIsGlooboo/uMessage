package com.glooboo.umessage.ui.settings.search

import java.text.Normalizer
import java.util.Locale

/**
 * Text clean-up for the settings search: lower case, no accents or Arabic vowel marks, one spelling for the Persian
 * and Arabic look-alike letters, ASCII digits, and no zero-width joiner, so "پیام‌ها" and "پیامها" read the same.
 */
internal object SearchText {
    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'
    private const val TATWEEL = 'ـ'
    private const val PERSIAN_ZERO = '۰'
    private const val ARABIC_ZERO = '٠'
    private const val DIGIT_COUNT = 10

    private val letterMap = mapOf(
        'ي' to 'ی', // Arabic yeh -> Persian yeh
        'ى' to 'ی', // alef maksura
        'ك' to 'ک', // Arabic kaf -> Persian keheh
        'ة' to 'ه', // teh marbuta -> heh
        'ہ' to 'ه', // heh goal
        'ھ' to 'ه', // heh doachashmee
        'ٱ' to 'ا', // alef wasla
    )

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (raw in decomposed) {
            when {
                raw == ZWNJ || raw == ZWJ || raw == TATWEEL -> Unit
                Character.getType(raw) == Character.NON_SPACING_MARK.toInt() -> Unit
                else -> out.append(mapChar(raw))
            }
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    private fun mapChar(char: Char): Char = when {
        char in PERSIAN_ZERO..(PERSIAN_ZERO + DIGIT_COUNT - 1) -> '0' + (char - PERSIAN_ZERO)
        char in ARABIC_ZERO..(ARABIC_ZERO + DIGIT_COUNT - 1) -> '0' + (char - ARABIC_ZERO)
        else -> letterMap[char] ?: char
    }

    /** The words of [text] after [normalize]: runs of letters and digits. */
    fun tokens(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (char in normalize(text)) {
            if (char.isLetterOrDigit()) {
                current.append(char)
            } else if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    /** Edit distance where swapping two neighbouring letters counts as one edit. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val rows = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) rows[i][0] = i
        for (j in 0..b.length) rows[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1, rows[i - 1][j - 1] + cost)
                if (swapped(a, b, i, j)) {
                    best = minOf(best, rows[i - 2][j - 2] + 1)
                }
                rows[i][j] = best
            }
        }
        return rows[a.length][b.length]
    }

    private fun swapped(a: String, b: String, i: Int, j: Int): Boolean =
        i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
}
