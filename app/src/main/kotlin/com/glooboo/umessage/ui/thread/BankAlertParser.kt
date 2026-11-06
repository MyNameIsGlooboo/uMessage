package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.helpers.CategoryKeywords
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.TextNormalizer
import java.math.BigDecimal

/** What a bank text reports. */
enum class BankAlertKind { Deposit, Withdrawal, Balance }

/** The currency of a bank alert, from its word or sign in the text. */
enum class BankCurrency {
    Rial, Toman, Usd, Eur, Gbp, Aed;

    companion object {
        fun ofWord(word: String): BankCurrency? = when (word.lowercase()) {
            "ریال", "rial", "rials", "rls", "irr" -> Rial
            "تومان", "toman" -> Toman
            "usd", "dollar", "dollars" -> Usd
            "eur" -> Eur
            "gbp" -> Gbp
            "aed" -> Aed
            else -> null
        }

        fun ofSymbol(symbol: Char): BankCurrency? = when (symbol) {
            '$' -> Usd
            '€' -> Eur
            '£' -> Gbp
            else -> null
        }
    }
}

/** What the last digits in a bank text belong to. */
enum class BankHintKind { Card, Account, Other }

/** The last four digits a bank text shows of a card or account. */
data class BankHint(val kind: BankHintKind, val digits: String)

/** One bank text read as figures. [amount] is null for [BankAlertKind.Balance]; [balance] is null when none shows. */
data class BankAlert(
    val kind: BankAlertKind,
    val amount: BigDecimal?,
    val balance: BigDecimal?,
    val currency: BankCurrency?,
    val hint: BankHint?,
)

/**
 * I31: reads the amount, the balance and the card digits out of a bank text, by its shape and not by the bank.
 * Pure JVM.
 *
 * A wrong amount is worse than none, so [parse] answers only when the whole text is clear and gives null at the first
 * doubt: two different amounts, a deposit word next to a withdrawal word, a sign that disagrees with the word, a
 * balance with a minus, two currencies, "500 هزار تومان" (the figure is not the sum), a link, a limit or a condition
 * ("if you did not..."), a loan or a prize. A number counts as money only with a currency after it or a label before
 * it ("مبلغ", "مانده", "amount", "balance"); card digits, dates and reference numbers are never read as money.
 * The caller decides first whether the text is a bank text at all ([MessageCategories.classify]).
 */
object BankAlertParser {

    fun parse(body: String): BankAlert? {
        val text = TextNormalizer.fold(body).replace('\u066C', ',').replace('\u066B', '.')
        val figures = text.takeUnless { isUnreadable(it) }?.let { findFigures(it) } ?: return null
        val currencies = figures.mapNotNull { it.currency }.toSet()
        val sums = figures.filter { !it.isBalance }
        val balances = figures.filter { it.isBalance }
        val unclear = currencies.size > 1 || sums.distinctValues() > 1 || balances.distinctValues() > 1 ||
            balances.any { it.sign == '-' }
        val kind = (if (unclear) null else kindOf(text, sums)) ?: return null
        val empty = when (kind) {
            BankAlertKind.Balance -> balances.isEmpty()
            else -> sums.first().value.signum() <= 0
        }
        if (empty) return null
        return BankAlert(
            kind = kind,
            amount = sums.firstOrNull()?.value,
            balance = balances.firstOrNull()?.value,
            currency = currencies.singleOrNull(),
            hint = hintOf(text),
        )
    }

    private fun isUnreadable(text: String): Boolean =
        text.length > MAX_LENGTH || REJECT.containsMatchIn(text) || OtpCodeExtractor.locate(text) != null

    /** One number that counts as money. [sign] is the plus or minus written right against it. */
    private class Figure(val value: BigDecimal, val sign: Char?, val currency: BankCurrency?, val isBalance: Boolean)

    /** Stands for a figure that cannot be read for certain, so the whole text is not read. */
    private val UNREADABLE = Figure(BigDecimal.ZERO, null, null, false)

    private fun List<Figure>.distinctValues(): Int = map { it.value.stripTrailingZeros() }.distinct().size

    /** Every figure of [text], or null when one of them cannot be read for certain. */
    private fun findFigures(text: String): List<Figure>? {
        val figures = mutableListOf<Figure>()
        for (match in NUMBER.findAll(text)) {
            val figure = figureAt(text, match)
            if (figure === UNREADABLE) return null
            if (figure != null) figures += figure
        }
        return figures
    }

    /** The figure at [match]; null when it is not money, [UNREADABLE] when it cannot be read for certain. */
    private fun figureAt(text: String, match: MatchResult): Figure? {
        val before = text.substring(maxOf(0, match.range.first - LOOKBACK), match.range.first)
        val after = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 1 + LOOKAHEAD))
        val unit = CURRENCY_AFTER.find(after)
        val word = unit?.groupValues?.get(1)?.let { BankCurrency.ofWord(it) }
        val symbol = SYMBOL_BEFORE.find(before)?.groupValues?.get(1)?.let { BankCurrency.ofSymbol(it.single()) }
        val balance = BALANCE_LABEL.containsMatchIn(before)
        val money = word != null || symbol != null || balance || AMOUNT_LABEL.containsMatchIn(before)
        return when {
            MULTIPLIER.containsMatchIn(after) || (word != null && symbol != null && word != symbol) -> UNREADABLE
            !money -> null
            else -> signedFigure(match, after, unit, word ?: symbol, balance)
        }
    }

    private fun signedFigure(
        match: MatchResult,
        after: String,
        unit: MatchResult?,
        currency: BankCurrency?,
        balance: Boolean,
    ): Figure {
        val leading = match.groupValues[1].firstOrNull()
        val trailing = signAt(after, 0) ?: unit?.let { signAt(after, it.range.last + 1) }
        if (leading != null && trailing != null && leading != trailing) return UNREADABLE
        val value = BigDecimal(match.groupValues[2].replace(",", ""))
        return Figure(value, leading ?: trailing, currency, balance)
    }

    /** The plus or minus at [index] of [text] when nothing but a space or a mark follows it. */
    private fun signAt(text: String, index: Int): Char? =
        text.getOrNull(index)?.takeIf { it in SIGNS && !text.getOrElse(index + 1) { ' ' }.isLetterOrDigit() }

    /** The kind, or null when the words or signs disagree or the text says nothing about money moving. */
    private fun kindOf(text: String, sums: List<Figure>): BankAlertKind? {
        val plain = WITHDRAWABLE.replace(text, " ")
        val deposit = DEPOSIT.containsMatchIn(plain)
        val withdrawal = WITHDRAWAL.containsMatchIn(plain)
        val kind = when {
            deposit && withdrawal -> null
            deposit -> BankAlertKind.Deposit
            withdrawal -> BankAlertKind.Withdrawal
            sums.isEmpty() -> BankAlertKind.Balance
            else -> null
        }
        val signs = sums.mapNotNull { it.sign }
        val disagrees = (kind == BankAlertKind.Deposit && '-' in signs) ||
            (kind == BankAlertKind.Withdrawal && '+' in signs)
        val moved = kind == BankAlertKind.Deposit || kind == BankAlertKind.Withdrawal
        return if (disagrees || (moved && sums.isEmpty())) null else kind
    }

    /** The last four digits when the text shows exactly one masked card or account number. */
    private fun hintOf(text: String): BankHint? {
        val found = (MASKED.findAll(text) + ENDING.findAll(text)).toList()
        if (found.map { it.groupValues[1] }.distinct().size != 1) return null
        val first = found.minBy { it.range.first }
        val before = text.substring(maxOf(0, first.range.first - LOOKBACK), first.range.first)
        val kind = when {
            CARD_WORD.containsMatchIn(before) -> BankHintKind.Card
            ACCOUNT_WORD.containsMatchIn(before) -> BankHintKind.Account
            else -> BankHintKind.Other
        }
        return BankHint(kind, first.groupValues[1])
    }

    private const val MAX_LENGTH = 600
    private const val LOOKBACK = 30
    private const val LOOKAHEAD = 14
    private const val SIGNS = "+-"

    private const val NUM = """\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?"""

    /** A whole number, not glued to letters or digits, not part of a date, time, masked number or percentage. */
    private val NUMBER = Regex("""(?<![\p{L}\p{N}.,/:*])([+-]?)($NUM)(?![\p{N}]|[.,/:]\p{N}|%)""")

    private val CURRENCY_AFTER = Regex(
        "^[+-]?\\s{0,2}(" + CategoryKeywords.CURRENCY.joinToString("|") { Regex.escape(TextNormalizer.fold(it)) } +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )
    private val SYMBOL_BEFORE = Regex("""([\x24€£])\s?$""")
    private val MULTIPLIER = Regex(
        """^\s*(هزار|میلیون|میلیارد|thousand|million|billion|[km](?![\p{L}]))""",
        RegexOption.IGNORE_CASE,
    )
    private val BALANCE_LABEL = Regex("""(مانده|موجودی|balance)[^\d.!?؟\n]{0,20}$""", RegexOption.IGNORE_CASE)
    private val AMOUNT_LABEL = Regex("""(مبلغ|amount)[^\d.!?؟\n]{0,12}$""", RegexOption.IGNORE_CASE)

    private val WITHDRAWABLE = Regex("""قابل\s*برداشت|withdrawable""", RegexOption.IGNORE_CASE)
    private val DEPOSIT = words("واریز", "deposit", "deposited", "credited", "بستانکار")
    private val WITHDRAWAL = words(
        "برداشت", "withdrawal", "withdrawn", "withdrew", "debited", "بدهکار", "خرید", "purchase", "purchased",
    )

    /** Texts that carry a figure but not a plain report: a link, a limit, a condition, a loan, a prize. */
    private val REJECT = Regex(
        """(https?://|www\.)|(?<![a-z0-9])[a-z0-9][a-z0-9-]*\.(ir|com|net|org|shop|store|link|me|app|co)""" +
            """(?![a-z0-9])|""" +
            words(
                "اگر", "در صورت", "if", "سقف", "limit", "حداکثر", "maximum", "حداقل", "minimum", "وام", "loan", "قسط",
                "installment", "جایزه", "prize", "برنده", "won", "win",
            ).pattern,
        RegexOption.IGNORE_CASE,
    )

    private val MASKED = Regex("""(?<![a-z])[*x•#]{2,}[ -]?(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val ENDING = Regex("""ending(?:\s+(?:in|with))?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
    private val CARD_WORD = Regex("""کارت|card""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_WORD = Regex("""حساب|account|acct|a/c""", RegexOption.IGNORE_CASE)

    /** Latin words match whole words only; Persian words match anywhere, as in the category lists. */
    private fun words(vararg list: String): Regex = Regex(
        list.joinToString("|") { word ->
            val folded = TextNormalizer.fold(word)
            val latin = folded.first() in 'a'..'z'
            (if (latin) "(?<![a-z0-9])" else "") + Regex.escape(folded) + (if (latin) "(?![a-z0-9])" else "")
        },
        RegexOption.IGNORE_CASE,
    )
}
