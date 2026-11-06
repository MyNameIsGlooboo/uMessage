package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.TextNormalizer

/** The kind of a conversation, for the Transactions and Offers filters. Anything unsure is [Other]. */
enum class MessageCategory { Personal, Transactions, Offers, Other }

/**
 * A small on-device guess of what a text is, from its sender and words alone; nothing leaves the phone. Pure JVM.
 *
 * It is deliberately cautious: a text in the wrong category is worse than one left in [MessageCategory.Other], which
 * only shows under "all". So a category needs several signs at once, and the sender must look like a service (a name
 * or a short line), never a person's mobile number or a saved contact:
 * - Transactions: an amount with a currency (or after "مبلغ", "balance"...) and a money word (واریز, برداشت, مانده...).
 * - Offers: a promotion word and a link, and no amount.
 * - A one-time code, or a text with both signs, is [MessageCategory.Other].
 * The word lists are in [CategoryKeywords]. Wave 2 (bank cards) and the weekly digest reuse this class.
 */
object MessageCategories {

    /**
     * The category of one text. [isContact] is true for a saved contact or a group; [userSent] is true when the user
     * has written in the thread. Either one makes it [MessageCategory.Personal] whatever the words say.
     */
    fun classify(address: String, body: String, isContact: Boolean, userSent: Boolean = false): MessageCategory {
        if (isContact || userSent) return MessageCategory.Personal
        if (!isServiceSender(address)) return MessageCategory.Other
        val text = TextNormalizer.fold(body)
        if (text.isBlank() || ONE_TIME_CODE.containsMatchIn(text)) return MessageCategory.Other
        val amount = hasAmount(text)
        val promo = PROMO.containsMatchIn(text)
        return when {
            promo && !amount && LINK.containsMatchIn(text) -> MessageCategory.Offers
            !promo && amount && TRANSACTION.containsMatchIn(text) -> MessageCategory.Transactions
            else -> MessageCategory.Other
        }
    }

    /** True when [text] (already folded) holds a sum of money. */
    internal fun hasAmount(text: String): Boolean = AMOUNT.containsMatchIn(text)

    /**
     * A sender that is a business or a bank, not a person: a name with letters ("BankName"), or a short line without
     * a plus sign and not a mobile number. Mobile numbers, landlines and international numbers read as people.
     */
    internal fun isServiceSender(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        val folded = TextNormalizer.fold(trimmed)
        return folded.any { it.isLetter() } || isShortLine(folded)
    }

    /** A number that reads as a service line: digits only, not international, not a mobile or landline. */
    private fun isShortLine(folded: String): Boolean {
        if (!folded.all { it.isDigit() || it in NUMBER_SYMBOLS }) return false
        val digits = folded.filter { it.isDigit() }
        val localCode = folded.startsWith("+98") || folded.startsWith("0098")
        if (folded.startsWith("+") && !localCode) return false
        val national = if (localCode) digits.removePrefix("98").removePrefix("0098") else digits
        return national.length in MIN_LINE_DIGITS..MAX_LINE_DIGITS &&
            !national.startsWith("0") &&
            !MOBILE.matches(national)
    }

    private const val MIN_LINE_DIGITS = 3
    private const val MAX_LINE_DIGITS = 12
    private const val NUMBER_SYMBOLS = "+- ()"

    /** A local mobile number without its leading zero. */
    private val MOBILE = Regex("9\\d{9}")

    private val ONE_TIME_CODE = words(CategoryKeywords.ONE_TIME_CODE)
    private val PROMO = words(CategoryKeywords.PROMO)
    private val TRANSACTION = words(CategoryKeywords.TRANSACTION)

    private val LINK = Regex(
        "(https?://|www\\.)\\S+|\\b[a-z0-9][a-z0-9-]*\\.(ir|com|net|org|shop|store|link|me|app|co|info)\\b",
        RegexOption.IGNORE_CASE,
    )

    private const val NUMBER = "\\d[\\d,.\u066C\u066B]*"
    private val AMOUNT = Regex(
        "$NUMBER\\s*(${alternatives(CategoryKeywords.CURRENCY)})(?![a-z])|" +
            "[\$\u20AC\u00A3]\\s*\\d|" +
            "(${alternatives(CategoryKeywords.AMOUNT_LABEL)})\\s*[:\uFF1A]?\\s*[+-]?\\s*\\d",
        RegexOption.IGNORE_CASE,
    )

    private fun alternatives(list: List<String>): String =
        list.joinToString("|") { Regex.escape(TextNormalizer.fold(it)) }

    /** Latin words need a word edge on both sides; Persian words are found anywhere. */
    private fun words(list: List<String>): Regex = Regex(
        list.joinToString("|") { word ->
            val folded = TextNormalizer.fold(word)
            val escaped = Regex.escape(folded)
            val latinStart = folded.first() in 'a'..'z' || folded.first() in 'A'..'Z'
            val latinEnd = folded.last() in 'a'..'z' || folded.last() in 'A'..'Z'
            (if (latinStart) "(?<![a-z0-9])" else "") + escaped + (if (latinEnd) "(?![a-z0-9])" else "")
        },
        RegexOption.IGNORE_CASE,
    )
}
