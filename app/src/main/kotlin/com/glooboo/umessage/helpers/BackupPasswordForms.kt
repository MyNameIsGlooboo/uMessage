package com.glooboo.umessage.helpers

import java.text.Normalizer

/**
 * I8: the forms of a backup password that open a protected file. A Persian typist gets different characters for the
 * same password on different keyboards (Arabic or Persian yeh and kaf, Latin, Persian or Arabic-Indic digits, a
 * zero-width non-joiner). Files written since the folded form was introduced (`UMSGENC2`) derive their key from
 * [folded], so the password works whichever keyboard it is typed on; files of the first version (`UMSGENC1`) used
 * [plain] only and are opened with that, then with [folded] as a fallback.
 */
internal object BackupPasswordForms {

    /** The password as typed, only with accents settled to one Unicode form (the first file version). */
    fun plain(password: CharArray): CharArray =
        Normalizer.normalize(String(password), Normalizer.Form.NFC).toCharArray()

    /** [plain], then folded: Persian and Arabic letters and digits agree, zero-width marks are dropped. */
    fun folded(password: CharArray): CharArray {
        val out = StringBuilder(password.size)
        for (c in String(plain(password))) {
            when {
                c in IGNORED -> Unit
                c == ARABIC_YEH || c == ALEF_MAKSURA -> out.append(PERSIAN_YEH)
                c == ARABIC_KAF -> out.append(PERSIAN_KAF)
                c in PERSIAN_ZERO..PERSIAN_NINE -> out.append('0' + (c - PERSIAN_ZERO))
                c in ARABIC_ZERO..ARABIC_NINE -> out.append('0' + (c - ARABIC_ZERO))
                else -> out.append(c)
            }
        }
        // A password made only of invisible marks keeps its plain form rather than becoming empty.
        return if (out.isEmpty()) plain(password) else out.toString().toCharArray()
    }

    /** The forms to try for a file of [version] (1 or 2), without repeats. */
    fun candidates(password: CharArray, version: Int): List<CharArray> {
        val folded = folded(password)
        if (version >= 2) return listOf(folded)
        val plain = plain(password)
        return if (plain.contentEquals(folded)) listOf(plain) else listOf(plain, folded)
    }

    // Fixed here, not taken from the shared search folding: changing that must never change which passwords open
    // existing files.
    private const val ARABIC_YEH = '\u064A'
    private const val ALEF_MAKSURA = '\u0649'
    private const val PERSIAN_YEH = '\u06CC'
    private const val ARABIC_KAF = '\u0643'
    private const val PERSIAN_KAF = '\u06A9'
    private const val PERSIAN_ZERO = '\u06F0'
    private const val PERSIAN_NINE = '\u06F9'
    private const val ARABIC_ZERO = '\u0660'
    private const val ARABIC_NINE = '\u0669'

    /** Zero-width marks, soft hyphen and kashida: invisible, so never part of the password. */
    private val IGNORED = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u00AD', '\u0640')
}
