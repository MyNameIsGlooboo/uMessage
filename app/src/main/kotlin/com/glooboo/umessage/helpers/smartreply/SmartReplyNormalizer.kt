package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.spam.TextNormalizer
import java.text.Normalizer
import java.util.Locale

/** The text form smart replies compare in; see [SmartReplyText.normalize]. Pure JVM. */
internal object SmartReplyNormalizer {

    fun normalize(text: String): String {
        val lowered = TextNormalizer.fold(text).lowercase(Locale.ROOT).replace(COMBINING_DOT, "")
        val spaced = StringBuilder(lowered.length)
        for (c in lowered) {
            if (c !in APOSTROPHES) appendReadable(spaced, c)
        }
        val words = REPEATED_LETTER.replace(spaced, "\$1").split(' ').filter { it.isNotEmpty() }
        return joinVerbPrefixes(words).joinToString(" ")
    }

    private fun appendReadable(out: StringBuilder, c: Char) {
        val type = Character.getType(c)
        val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        when {
            mark && keepsMarks(c) -> out.append(c)
            type == Character.NON_SPACING_MARK.toInt() -> Unit
            else -> {
                val letter = canonical(c)
                out.append(if (letter.all { it.isLetterOrDigit() }) letter else " ")
            }
        }
    }

    /** Vowel and tone signs of these scripts are part of the word; for Latin and Arabic they are noise. */
    private fun keepsMarks(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.DEVANAGARI,
        Character.UnicodeScript.BENGALI,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        -> true

        else -> c == VOICED_SOUND_MARK || c == SEMI_VOICED_SOUND_MARK
    }

    private fun canonical(c: Char): String {
        val joined = LETTER_VARIANTS[c]
        return when {
            joined != null -> joined.toString()
            c in FULL_WIDTH_ASCII -> (c - FULL_WIDTH_OFFSET).toString()
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN ->
                Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

            else -> c.toString()
        }
    }

    private fun joinVerbPrefixes(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (words[i] in VERB_PREFIXES && i + 1 < words.size) {
                out.add(words[i] + words[i + 1])
                i += 2
            } else {
                out.add(words[i])
                i++
            }
        }
        return out
    }

    private const val COMBINING_DOT = "̇"
    private const val APOSTROPHES = "'’ʼ`"
    private const val VOICED_SOUND_MARK = '゙'
    private const val SEMI_VOICED_SOUND_MARK = '゚'
    private const val FULL_WIDTH_OFFSET = 0xFEE0
    private val FULL_WIDTH_ASCII = '！'..'～'

    /** The Persian verb prefixes "می" and "نمی": typed with a ZWNJ (removed by the fold) or with a space. */
    private val VERB_PREFIXES = setOf("می", "نمی")

    /** Letters that read as another letter: dotless i, Arabic alef with hamza, teh marbuta, Russian yo. */
    private val LETTER_VARIANTS = mapOf(
        'ı' to 'i',
        'أ' to 'ا',
        'إ' to 'ا',
        'ٱ' to 'ا',
        'ة' to 'ه',
        'ё' to 'е',
    )

    private val REPEATED_LETTER = Regex("""(\p{L})\1{2,}""")
}
