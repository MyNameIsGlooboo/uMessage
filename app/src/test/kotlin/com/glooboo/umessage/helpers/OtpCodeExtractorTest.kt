package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpCodeExtractorTest {

    @Test fun plainCode() = assertEquals("482913", OtpCodeExtractor.extract("Your verification code is 482913"))

    @Test fun spacedCode() = assertEquals("482913", OtpCodeExtractor.extract("Login code: 482 913"))

    @Test fun pinWord() = assertEquals("4821", OtpCodeExtractor.extract("Your PIN is 4821."))

    // "pin" inside "shipping" and "code" inside "barcode" are not keywords.
    @Test fun noKeywordInsideWords() {
        assertNull(OtpCodeExtractor.extract("Your shipping update for order 2026 is ready"))
        assertNull(OtpCodeExtractor.extract("Scan the barcode 12345678 at the counter"))
    }

    @Test fun closestNumberToKeywordWins() = assertEquals(
        "739104",
        OtpCodeExtractor.extract("Order 2026 shipped. Use code 739104 to confirm delivery.")
    )

    @Test fun signInVariant() = assertEquals("5521", OtpCodeExtractor.extract("5521 is your sign-in number"))

    @Test fun noDigits() = assertNull(OtpCodeExtractor.extract("Your code will arrive shortly"))

    @Test fun hyphenatedCode() = assertEquals("482913", OtpCodeExtractor.extract("Your code: 482-913"))

    // Persian and Arabic bank messages. The bodies are invented.

    @Test fun persianBankMultiline() = assertEquals(
        "52814736",
        OtpCodeExtractor.extract(
            "بانک پاسارگاد\nرمز پویا: 52814736\nمبلغ: 1,250,000 ریال\nکارت: 5022-29**-****-4417\nزمان: 14:32"
        )
    )

    @Test fun persianDigitsComeBackAscii() = assertEquals(
        "52814736",
        OtpCodeExtractor.extract(
            "بانک پاسارگاد\nرمز پویا: ۵۲۸۱۴۷۳۶\nمبلغ: ۱٬۲۵۰٬۰۰۰ ریال\nکارت: ۵۰۲۲-۲۹**-****-۴۴۱۷\nزمان: ۱۴:۳۲"
        )
    )

    @Test fun secondDynamicPassword() = assertEquals("842156", OtpCodeExtractor.extract("رمز دوم پویای شما ۸۴۲۱۵۶ است"))

    @Test fun secondDynamicPasswordLatinDigits() =
        assertEquals("12345678", OtpCodeExtractor.extract("رمز دوم پویا 12345678"))

    @Test fun mobileBankLoginCode() =
        assertEquals("73915", OtpCodeExtractor.extract("کد تایید ورود به همراه بانک: 73915"))

    @Test fun verificationCodeWithZwnj() =
        assertEquals("4821", OtpCodeExtractor.extract("کد‌تایید شما 4821"))

    @Test fun possessiveSuffix() = assertEquals("6610", OtpCodeExtractor.extract("کدتان ۶۶۱۰ است"))

    @Test fun oneTimePassword() = assertEquals("903311", OtpCodeExtractor.extract("رمز یکبار مصرف: ۹۰۳۳۱۱"))

    @Test fun arabicVerificationCode() =
        assertEquals("552019", OtpCodeExtractor.extract("رمز التحقق الخاص بك هو ٥٥٢٠١٩"))

    @Test fun codeAfterAmountAndDate() = assertEquals(
        "4471",
        OtpCodeExtractor.extract("مبلغ 2500000 ریال در تاریخ 1403/08/11 ساعت 09:15\nرمز پویا 4471")
    )

    @Test fun neverAnAmount() {
        assertNull(OtpCodeExtractor.extract("رمز خرید شما: مبلغ 250000 تومان"))
        assertNull(OtpCodeExtractor.extract("Your password reset fee is 1500 rials"))
        assertNull(OtpCodeExtractor.extract("Verification fee 1,500,000"))
        assertNull(OtpCodeExtractor.extract("کد تخفیف 2500%"))
    }

    @Test fun neverAMaskedCardOrAccount() {
        assertNull(OtpCodeExtractor.extract("رمز کارت 5022-29**-****-4417 تغییر کرد"))
        assertNull(OtpCodeExtractor.extract("Your PIN for card ****1234 was changed"))
        assertNull(OtpCodeExtractor.extract("PIN for account 1234**** changed"))
    }

    @Test fun neverATimeOrADate() {
        assertNull(OtpCodeExtractor.extract("رمز پویا در 1403/08/11 ساعت 14:32 غیرفعال شد"))
        assertNull(OtpCodeExtractor.extract("Login at 2026-11-02 12:30"))
    }

    @Test fun maskedCardDoesNotBeatRealCode() =
        assertEquals("620417", OtpCodeExtractor.extract("کارت ****4417 رمز 620417"))

    @Test fun yearIsNotAGenericCode() = assertNull(OtpCodeExtractor.extract("کد ملی سال 1403"))
}
