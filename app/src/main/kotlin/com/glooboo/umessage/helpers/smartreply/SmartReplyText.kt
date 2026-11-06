package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.spam.TextNormalizer
import java.text.Normalizer
import java.util.Locale

/**
 * How smart replies read a text. [normalize] gives the form phrases are compared in: [TextNormalizer.fold] (Persian
 * letters, digits, zero-width characters), lower case, apostrophes dropped ("can't" is "cant"), accents and the Turkish
 * dotless i read as plain letters, a few Arabic letter variants joined, every other symbol a space, a letter repeated
 * three or more times written once ("heyyy", "سلاااام"), and Persian "می" / "نمی" joined to the next word. Vowel
 * signs of Thai and the Indic scripts stay in the word; full-width Latin letters read as plain ones. Pure JVM.
 *
 * Scripts with no spaces between words (Chinese, Japanese, Thai) and Korean, where spacing is loose, are *compact*:
 * their texts and phrases are compared with the spaces removed ([compact]), phrases match anywhere inside a text
 * ([containsPhrase]), and a text is measured in characters, not words ([length]).
 */
internal object SmartReplyText {

    /** The scripts a table can read. [tableName] is the `script` value of a table file. */
    enum class Script(val tableName: String, val compact: Boolean = false) {
        Latin("latin"),
        Arabic("arabic"),
        Han("han", compact = true),
        Japanese("japanese", compact = true),
        Hangul("hangul", compact = true),
        Thai("thai", compact = true),
        Devanagari("devanagari"),
        Bengali("bengali"),
        Cyrillic("cyrillic"),
        ;

        /** Whether a table of this script reads a text written in [text] (a Japanese table also reads kanji only). */
        fun reads(text: Script): Boolean = this == text || (this == Japanese && text == Han)

        companion object {
            /** An unknown name reads as Latin, as every table did before scripts were named. */
            fun named(name: String): Script = entries.firstOrNull { it.tableName == name } ?: Latin
        }
    }

    fun normalize(text: String): String = SmartReplyNormalizer.normalize(text)

    /** The one script a normalised text is written in, or null for mixed, other or letterless text. */
    fun script(normalized: String): Script? {
        val seen = HashSet<Script>()
        for (c in normalized) {
            if (!c.isLetter()) continue
            seen.add(scriptOf(c) ?: return null)
        }
        return when {
            seen.size == 1 -> seen.first()
            seen == setOf(Script.Han, Script.Japanese) -> Script.Japanese
            else -> null
        }
    }

    /** A normalised text with its spaces removed: the form compact scripts are compared in. */
    fun compact(normalized: String): String = normalized.replace(" ", "")

    /** Characters of a compact text, vowel and tone signs not counted (they ride on a letter). */
    fun length(compactText: String): Int =
        compactText.count { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** Groups of question marks (Latin, Arabic or full-width): "??" is one, "a? b?" is two. */
    fun questionGroups(body: String): Int = QUESTION_MARKS.findAll(body).count()

    fun hasQuestion(body: String): Boolean = body.any { it in QUESTION_CHARS }

    /** True when [phrase] (normalised, non-empty) stands in [text] (normalised) as whole words. */
    fun containsWords(text: String, phrase: String): Boolean =
        phrase.isNotEmpty() && " $text ".contains(" $phrase ")

    /**
     * Whether [phrase] stands in [text]: as whole words for a spaced script; for a compact one both must be the
     * [compact] form and the phrase may sit anywhere in the text.
     */
    fun containsPhrase(text: String, phrase: String, compact: Boolean): Boolean =
        if (compact) phrase.isNotEmpty() && text.contains(phrase) else containsWords(text, phrase)

    private fun scriptOf(c: Char): Script? = when {
        isArabicScript(c) -> Script.Arabic
        c.code < LATIN_LIMIT -> Script.Latin
        c == PROLONGED_SOUND || c == HALFWIDTH_PROLONGED_SOUND -> Script.Japanese
        else -> when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN -> Script.Han
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.Japanese
            Character.UnicodeScript.HANGUL -> Script.Hangul
            Character.UnicodeScript.THAI -> Script.Thai
            Character.UnicodeScript.DEVANAGARI -> Script.Devanagari
            Character.UnicodeScript.BENGALI -> Script.Bengali
            Character.UnicodeScript.CYRILLIC -> Script.Cyrillic
            else -> null
        }
    }

    private fun isArabicScript(c: Char): Boolean = c in ARABIC_BLOCK || c in ARABIC_SUPPLEMENT ||
        c in ARABIC_PRESENTATION_A || c in ARABIC_PRESENTATION_B

    private const val QUESTION_CHARS = "?؟？"
    private const val LATIN_LIMIT = 0x250
    private const val PROLONGED_SOUND = 'ー'
    private const val HALFWIDTH_PROLONGED_SOUND = 'ｰ'
    private val ARABIC_BLOCK = '؀'..'ۿ'
    private val ARABIC_SUPPLEMENT = 'ݐ'..'ݿ'
    private val ARABIC_PRESENTATION_A = 'ﭐ'..'﷿'
    private val ARABIC_PRESENTATION_B = '\uFE70'..'\uFEFF'

    private val QUESTION_MARKS = Regex("[?؟？]+")
}
