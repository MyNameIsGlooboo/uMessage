package com.glooboo.umessage.helpers

import com.glooboo.umessage.helpers.MessageCategory.Offers
import com.glooboo.umessage.helpers.MessageCategory.Other
import com.glooboo.umessage.helpers.MessageCategory.Personal
import com.glooboo.umessage.helpers.MessageCategory.Transactions
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** I11: invented texts only. A wrong category is worse than a missing one, so many of these must stay [Other]. */
class MessageCategoriesTest {

    private val bank = "BankX"
    private val line = "5000123"
    private val mobile = "09000001111"

    private fun category(address: String, body: String, contact: Boolean = false) =
        MessageCategories.classify(address, body, isContact = contact)

    private fun assertAll(expected: MessageCategory, address: String, bodies: List<String>) {
        bodies.forEach { assertWithMessage(it).that(category(address, it)).isEqualTo(expected) }
    }

    @Test
    fun englishBankTextsAreTransactions() = assertAll(
        Transactions, bank,
        listOf(
            "Your account was debited 5,000 IRR. Balance: 120,000 IRR",
            "Deposit of \$250.00 received. Balance \$1,200.00",
            "Withdrawal 100 USD at ATM 4411",
            "Transaction: amount 45.50 EUR approved",
            "Credited 3,000,000 rials to your account",
            "Balance: 1,000 toman",
            "Transferred 250,000 toman to account 1234",
            "A deposit of 12.5 GBP was made",
            "Withdrawn 20 USD. Available balance 400 USD",
            "Debited €30 for card payment. Balance €970",
        ),
    )

    @Test
    fun persianBankTextsAreTransactions() = assertAll(
        Transactions, bank,
        listOf(
            "واریز مبلغ 5,000,000 ریال به حساب شما. مانده: 12,300,000 ریال",
            "برداشت ۲۰۰,۰۰۰ ریال از کارت. موجودی: ۸۰۰,۰۰۰ ریال",
            "تراکنش موفق\nمبلغ: 150,000 تومان\nمانده 2,000,000 تومان",
            "انتقال وجه ۳,۰۰۰,۰۰۰ ریال انجام شد",
            "مانده حساب شما 2,500,000 ریال است",
            "برداشت از حساب مبلغ 1000000 ریال",
            "موجودی: 9,800,000 ریال",
            "بستانکار +1,200,000 ریال",
            "واريز مبلغ ٥٠٠٠٠ ريال",
            "بدهکار 450000 ریال",
        ),
    )

    @Test
    fun serviceLinesCountAsServicesButMobilesDoNot() {
        val body = "Deposit 500,000 rials. Balance 1,000,000 rials"
        assertThat(category(line, body)).isEqualTo(Transactions)
        assertThat(category("+98 5000123", body)).isEqualTo(Transactions)
        assertThat(category(mobile, body)).isEqualTo(Other)
        assertThat(category("+989000001111", body)).isEqualTo(Other)
        assertThat(category("+14155550123", body)).isEqualTo(Other)
        assertThat(category("02112345678", body)).isEqualTo(Other)
        assertThat(category("", body)).isEqualTo(Other)
    }

    @Test
    fun englishPromotionsWithALinkAreOffers() = assertAll(
        Offers, "MyShop",
        listOf(
            "Big SALE! 50% off everything. Shop at www.myshop.com",
            "Use coupon CODE10 at myshop.ir/now",
            "Exclusive discount for you! https://shop.example.co/deal",
            "Special offer ends Friday: myshop.com/deals",
            "Free gift with your next order. Details: www.myshop.ir",
            "Promo week is here, see myshop.shop/promo",
        ),
    )

    @Test
    fun persianPromotionsWithALinkAreOffers() = assertAll(
        Offers, "MyShop",
        listOf(
            "جشنواره تخفیف ویژه تا ۵۰٪\nwww.myshop.ir",
            "کد تخفیف SALE20 برای شما: https://example.com/x",
            "حراج بزرگ زمستانه! لینک: myshop.ir/abc",
            "فروش ویژه فقط تا امشب https://myshop.ir/sale",
            "هدیه ویژه برای شما myshop.com",
            "پیشنهاد ویژه ما را ببینید: www.myshop.ir/offer",
        ),
    )

    @Test
    fun aPromotionWithoutALinkOrWithAPriceIsNotAnOffer() = assertAll(
        Other, "MyShop",
        listOf(
            "Huge discount today only",
            "Discount: only \$9.99 at myshop.com",
            "Sale: 20,000 toman at www.myshop.ir",
            "تخفیف ویژه امروز",
            "تخفیف فقط 100000 تومان myshop.ir",
        ),
    )

    @Test
    fun oneTimeCodesAreNeverACategory() = assertAll(
        Other, bank,
        listOf(
            "Your verification code is 123456",
            "Your OTP is 4821. Do not share it. Amount 500 USD",
            "Security code 5521 for payment of 1,000 toman",
            "کد تایید شما: ۱۲۳۴۵۶",
            "رمز پویا 12345 مبلغ 1000 ریال",
            "رمز یک‌بار مصرف شما 4455 است. موجودی 5000 ریال",
        ),
    )

    @Test
    fun aTextWithBothSignsOrNeitherStaysOther() = assertAll(
        Other, bank,
        listOf(
            "Sale! Deposit 500 USD and get 10% off at myshop.com",
            "Your balance is low. Visit www.bank.example.com",
            "Meeting moved to 5pm",
            "Purchase now and enjoy! www.myshop.com",
            "Your package arrives tomorrow",
            "",
            "   ",
            "موجودی انبار کم است",
            "خرید شما ثبت شد",
        ),
    )

    @Test
    fun aFriendTalkingAboutMoneyIsNotATransaction() {
        assertAll(
            Other, mobile,
            listOf(
                "I transferred 500,000 toman to your account, check the balance",
                "مبلغ رو واریز کردم 500000 تومان",
                "Big sale at myshop.com 50% off",
                "سلام، فردا میای؟",
            ),
        )
    }

    @Test
    fun aContactOrAThreadYouWroteInIsAlwaysPersonal() {
        assertThat(category(bank, "Deposit 500 USD. Balance 1,000 USD", contact = true)).isEqualTo(Personal)
        assertThat(category(mobile, "Big sale at myshop.com 50% off", contact = true)).isEqualTo(Personal)
        assertThat(MessageCategories.classify(bank, "Deposit 500 USD", isContact = false, userSent = true))
            .isEqualTo(Personal)
    }

    @Test
    fun latinWordsNeedWholeWords() {
        assertThat(category("MyShop", "Wholesale prices at myshop.com")).isEqualTo(Other)
        assertThat(category("MyShop", "Sales team visits myshop.com")).isEqualTo(Other)
        assertThat(category(bank, "Deposited 10 USD")).isEqualTo(Transactions)
    }
}
