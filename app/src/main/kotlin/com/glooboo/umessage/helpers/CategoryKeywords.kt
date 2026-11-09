package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.TextNormalizer

/**
 * The words [MessageCategories] looks for, English and Persian, in one place so they can be tuned. Every word is
 * written as a person would read it; [TextNormalizer.fold] is applied to them and to the message alike, so Persian
 * digits, Arabic ي/ك and zero-width marks make no difference. Latin words match whole words only ("off" is not
 * "offer"); Persian words match anywhere in a word, as Persian has no reliable word edges for a plain match.
 */
internal object CategoryKeywords {

    /** Words that name money moving or an account balance. "Purchase" and "خرید" are left out: promotions use them. */
    val TRANSACTION = listOf(
        "deposit", "deposited", "withdrawal", "withdrawn", "balance", "debited", "credited", "transaction",
        "transferred", "واریز", "برداشت", "مانده", "موجودی", "تراکنش", "انتقال وجه", "بدهکار", "بستانکار",
    )

    /** A bank's currency words; a number followed by one of them is an amount. */
    val CURRENCY = listOf(
        "ریال", "تومان", "rial", "rials", "rls", "irr", "toman", "usd", "eur", "gbp", "aed", "dollar", "dollars",
    )

    /** Words that put a number after them in a bank text: "مبلغ 500,000", "balance: 1,200". */
    val AMOUNT_LABEL = listOf("مبلغ", "مانده", "موجودی", "amount", "balance")

    /** Words of a promotion. */
    val PROMO = listOf(
        "discount", "coupon", "promo", "sale", "special offer", "limited offer", "% off", "free gift", "تخفیف",
        "کد تخفیف", "فروش ویژه", "جشنواره", "حراج", "هدیه", "ارزان‌تر", "پیشنهاد ویژه", "پیشنهاد شگفت‌انگیز",
    )
}
