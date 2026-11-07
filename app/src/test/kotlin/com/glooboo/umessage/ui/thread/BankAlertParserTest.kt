package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.math.BigDecimal
import org.junit.Test

/** I31: invented texts only. A wrong figure is worse than none, so the near-misses must give no card at all. */
class BankAlertParserTest {

    private fun plain(value: BigDecimal?): String? = value?.stripTrailingZeros()?.toPlainString()

    private fun check(
        body: String,
        kind: BankAlertKind,
        amount: String?,
        balance: String?,
        currency: BankCurrency?,
    ) {
        val alert = BankAlertParser.parse(body)
        assertWithMessage(body).that(alert).isNotNull()
        assertWithMessage(body).that(alert!!.kind).isEqualTo(kind)
        assertWithMessage(body).that(plain(alert.amount)).isEqualTo(amount)
        assertWithMessage(body).that(plain(alert.balance)).isEqualTo(balance)
        assertWithMessage(body).that(alert.currency).isEqualTo(currency)
    }

    private fun assertNone(bodies: List<String>) {
        bodies.forEach { assertWithMessage(it).that(BankAlertParser.parse(it)).isNull() }
    }

    @Test
    fun englishWithdrawalWithBalance() = check(
        "Your account was debited 5,000 IRR. Balance: 120,000 IRR",
        BankAlertKind.Withdrawal, "5000", "120000", BankCurrency.Rial,
    )

    @Test
    fun englishDepositInDollars() = check(
        "Deposit of \$250.00 received. Balance \$1,200.00",
        BankAlertKind.Deposit, "250", "1200", BankCurrency.Usd,
    )

    @Test
    fun englishDepositInRials() = check(
        "Credited 3,000,000 rials to your account",
        BankAlertKind.Deposit, "3000000", null, BankCurrency.Rial,
    )

    @Test
    fun englishAmountLabelWithoutCurrency() = check(
        "Withdrawal amount: 45.50 EUR approved",
        BankAlertKind.Withdrawal, "45.5", null, BankCurrency.Eur,
    )

    @Test
    fun persianDepositWithAccountAndBalance() {
        val body = "واریز ۵,۰۰۰,۰۰۰ ریال به حساب ****۱۲۳۴ مانده: ۱۲,۵۰۰,۰۰۰ ریال"
        check(body, BankAlertKind.Deposit, "5000000", "12500000", BankCurrency.Rial)
        assertThat(BankAlertParser.parse(body)!!.hint).isEqualTo(BankHint(BankHintKind.Account, "1234"))
    }

    @Test
    fun persianWithdrawalFromCard() {
        val body = "برداشت از کارت ****۶۰۳۷ مبلغ ۲۵۰,۰۰۰ تومان موجودی ۱,۰۰۰,۰۰۰ تومان"
        check(body, BankAlertKind.Withdrawal, "250000", "1000000", BankCurrency.Toman)
        assertThat(BankAlertParser.parse(body)!!.hint).isEqualTo(BankHint(BankHintKind.Card, "6037"))
    }

    @Test
    fun persianSeparatorsAndArabicDigits() = check(
        "واریز ٣٬٠٠٠٬٠٠٠ ریال",
        BankAlertKind.Deposit, "3000000", null, BankCurrency.Rial,
    )

    @Test
    fun persianLetterVariantsAreFolded() = check(
        "\u0628\u0631\u062F\u0627\u0634\u062A \u06F2\u06F0\u06F0\u06F0\u06F0 \u0631\u064A\u0627\u0644",
        BankAlertKind.Withdrawal, "20000", null, BankCurrency.Rial,
    )

    @Test
    fun signsAgreeingWithTheWord() {
        check("برداشت 500,000- ریال", BankAlertKind.Withdrawal, "500000", null, BankCurrency.Rial)
        check("Deposit +750,000 rial", BankAlertKind.Deposit, "750000", null, BankCurrency.Rial)
    }

    @Test
    fun balanceOnly() {
        check("مانده حساب شما ۱,۲۰۰,۰۰۰ ریال است", BankAlertKind.Balance, null, "1200000", BankCurrency.Rial)
        check("Your balance is 400 USD", BankAlertKind.Balance, null, "400", BankCurrency.Usd)
    }

    @Test
    fun withdrawableBalanceIsABalance() = check(
        "مانده قابل برداشت ۱,۰۰۰ ریال",
        BankAlertKind.Balance, null, "1000", BankCurrency.Rial,
    )

    @Test
    fun hintFromEndingDigits() {
        val alert = BankAlertParser.parse("Debited 30 USD, card ending in 4411")
        assertThat(alert!!.hint).isEqualTo(BankHint(BankHintKind.Card, "4411"))
    }

    @Test
    fun twoDifferentCardsGiveNoHint() {
        val alert = BankAlertParser.parse("Debited 30 USD from card ****1111 to card ****2222")
        assertThat(alert).isNotNull()
        assertThat(alert!!.hint).isNull()
    }

    @Test
    fun numbersThatAreNotMoneyAreIgnored() = check(
        "Withdrawal at ATM 4411 on 2026/03/08 at 14:32 ref 99887766: 100 USD",
        BankAlertKind.Withdrawal, "100", null, BankCurrency.Usd,
    )

    @Test
    fun twoDifferentAmountsGiveNoCard() = assertNone(
        listOf(
            "Withdrawal 500 USD at SHOP, fee 2 USD",
            "برداشت ۵۰۰,۰۰۰ ریال کارمزد ۵,۰۰۰ ریال",
            "Deposit 100 USD, withdrawal 50 USD",
        ),
    )

    @Test
    fun kindAndSignThatDisagreeGiveNoCard() = assertNone(
        listOf(
            "Deposit -500,000 Rial",
            "برداشت +500,000 ریال",
            "Deposit and withdrawal summary: 500 USD",
        ),
    )

    @Test
    fun aSumInThousandsOrMillionsGivesNoCard() = assertNone(
        listOf(
            "برداشت ۵۰۰ هزار تومان",
            "واریز مبلغ ۲ میلیون ریال",
            "Deposit amount 5 million IRR",
            "Withdrawal amount 2.5 thousand USD",
        ),
    )

    @Test
    fun limitsConditionsLoansAndPrizesGiveNoCard() = assertNone(
        listOf(
            "Daily withdrawal limit is 5,000 USD",
            "سقف برداشت روزانه شما ۵۰,۰۰۰,۰۰۰ ریال است",
            "If you did not withdraw 500 USD call the bank",
            "اگر برداشت ۵۰۰,۰۰۰ ریال کار شما نبود تماس بگیرید",
            "Loan installment 5,000,000 rial debited",
            "قسط وام ۲,۰۰۰,۰۰۰ ریال برداشت شد",
            "You won a prize: deposit 100 USD now",
        ),
    )

    @Test
    fun linksGiveNoCard() = assertNone(
        listOf(
            "Withdrawal 100 USD. Dispute at bank.com/help",
            "برداشت ۱۰۰,۰۰۰ ریال لغو: https://example.test/x",
        ),
    )

    @Test
    fun codesGiveNoCard() = assertNone(listOf("Your OTP is 481516 to confirm withdrawal of 100 USD"))

    @Test
    fun badNumbersGiveNoCard() = assertNone(
        listOf(
            "Withdrawal 12,34 USD",
            "Withdrawal 1,2,3 USD",
            "Deposit 5% bonus",
            "Withdrawal 100.123 USD",
        ),
    )

    @Test
    fun negativeBalanceGivesNoCard() = assertNone(listOf("Withdrawal 100 USD. Balance: -20 USD"))

    @Test
    fun mixedCurrenciesGiveNoCard() = assertNone(listOf("Deposit 100 USD. Balance 5,000,000 Rial"))

    @Test
    fun moneyMovingWordsWithoutAFigureGiveNoCard() = assertNone(
        listOf(
            "Withdrawal on 1403/08/11 14:32",
            "واریز انجام شد",
            "Your card ****1234 was used",
            "Deposit received",
        ),
    )

    @Test
    fun anAmountWithoutAWordGivesNoCard() = assertNone(
        listOf(
            "Special offer! Shoes only 500,000 toman",
            "Your order total is \$25.00",
        ),
    )

    @Test
    fun zeroAmountGivesNoCard() = assertNone(listOf("Deposit 0 USD"))

    @Test
    fun longTextGivesNoCard() = assertNone(listOf("Deposit 100 USD. " + "x ".repeat(400)))

    @Test
    fun gateNeedsAServiceSenderAndATransactionText() {
        val body = "Deposit of \$250.00 received. Balance \$1,200.00"
        assertThat(bankAlertOf("BankX", body)).isNotNull()
        assertThat(bankAlertOf("5000123", body)).isNotNull()
        assertThat(bankAlertOf("07121234567", body)).isNull()
        assertThat(bankAlertOf("+14155550123", body)).isNull()
        assertThat(bankAlertOf("BankX", "Hello, lunch tomorrow?")).isNull()
    }
}
