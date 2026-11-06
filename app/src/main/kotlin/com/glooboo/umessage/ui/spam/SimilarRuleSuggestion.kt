package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.TextNormalizer

/**
 * I22: the allow rule offered after "Not spam" for messages that look like the restored one, so the next one from the
 * same service is not held again. Only a sender that rotates its number or name gets one; a normal phone number is
 * already covered by "Always allow" for that number, so nothing is suggested for it.
 */
object SimilarRuleSuggestion {

    data class Suggestion(val type: RuleType, val pattern: String)

    private const val MIN_SENDER_NAME = 3
    private const val MIN_SHORT_CODE = 6
    private const val MAX_SHORT_CODE = 8
    private const val ROTATING_TAIL = 2
    private const val MIN_PREFIX = 4
    private const val MIN_WORD = 6

    private val carrierPrefix = Regex("^[A-Za-z0-9]{2}-(.+)$")
    private val urls = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val words = Regex("""\p{L}+""")

    /** The suggestion for a message from [address] with [body], or null when none fits. */
    fun suggest(address: String, body: String): Suggestion? {
        val value = address.trim()
        if (value.isEmpty()) return null
        val found = when {
            value.any { it.isLetter() } -> senderName(value) ?: keyword(body)
            else -> shortCodePrefix(value) ?: if (isPlainShortCode(value)) keyword(body) else null
        }
        return found?.takeIf { RuleValidator.validate(ListKind.ALLOW, it.type, it.pattern) is RuleValidation.Valid }
    }

    /** "AX-HDFCBK" and "VM-HDFCBK" are one service: allow the part after the carrier's two-letter tag. */
    private fun senderName(value: String): Suggestion? {
        val name = carrierPrefix.find(value)?.groupValues?.get(1)?.trim() ?: return null
        if (name.length < MIN_SENDER_NAME || '-' in name) return null
        return Suggestion(RuleType.SENDER_ID, name.uppercase())
    }

    /** A service number whose last digits change: allow the leading digits. */
    private fun shortCodePrefix(value: String): Suggestion? {
        if (!isPlainShortCode(value) || value.length !in MIN_SHORT_CODE..MAX_SHORT_CODE) return null
        val prefix = value.dropLast(ROTATING_TAIL)
        return if (prefix.length >= MIN_PREFIX) Suggestion(RuleType.PREFIX, prefix) else null
    }

    private fun isPlainShortCode(value: String) =
        value.isNotEmpty() && value.all { it in '0'..'9' } && !value.startsWith("0")

    private fun isCommon(word: String): Boolean =
        RuleWarning.COMMON_WORD in RuleValidator.warnings(ListKind.ALLOW, RuleType.KEYWORD, word, emptyList(), null)

    /** The longest word of the text that is not a common one, folded. Null when the text has none. */
    private fun keyword(body: String): Suggestion? {
        val text = TextNormalizer.lower(urls.replace(body, " "))
        val word = words.findAll(text).map { it.value }
            .filter { it.length >= MIN_WORD }
            .filter { !isCommon(it) }
            .maxByOrNull { it.length }
        return word?.let { Suggestion(RuleType.KEYWORD, it) }
    }
}
