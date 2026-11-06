package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SE-01, SE-02, SE-04: what counts as a verification code, and what counts as a link. */
class OtpAccuracyTest {

    private fun otp(body: String) = OtpDetector.looksLikeOtp(body)
    private fun link(body: String) = OtpDetector.containsUrl(body)

    // ---- SE-01: marketing text with a number is not a code ----

    @Test fun promoCodeWithYearIsNotAnOtp() {
        assertFalse(otp("Use promo code 2025 for 50% off today!"))
        assertFalse(otp("Coupon code 4821 gives you a free gift"))
        assertFalse(otp("Discount code: 7788, valid this week only"))
        assertFalse(otp("Your code 1234 = 30% off at checkout"))
    }

    @Test fun persianPromoTextsAreNotOtps() {
        assertFalse(otp("کد تخفیف ۵۰۲۰ برای خرید اول شما. لغو۱۱"))
        assertFalse(otp("جشنواره حراج! با کد 1403 تا ۷۰٪ تخفیف بگیرید"))
        assertFalse(otp("برنده جایزه شدید! کد قرعه کشی شما 884422"))
        assertFalse(otp("کد هدیه 2468 برای شارژ رایگان"))
        assertFalse(otp("با کد ۱۴۰۳ از تخفیف نوروزی استفاده کنید"))
    }

    @Test fun yearOrPriceIsNotACode() {
        assertFalse(otp("Your code is valid until 2025"))
        assertFalse(otp("کد اشتراک شما تا سال ۱۴۰۴ معتبر است"))
        assertFalse(otp("Code price 4500 تومان"))
        assertFalse(otp("code: 1500 ریال"))
    }

    @Test fun realCodesStillCount() {
        assertTrue(otp("Your Bank code: 483920"))
        assertTrue(otp("Your verification code is 2025"))
        assertTrue(otp("رمز پویا: ۱۲۳۴۵۶"))
        assertTrue(otp("رمز پویا خرید: 58231914\nمبلغ: 1,200,000 ریال\nبانک ملت"))
        assertTrue(otp("کد ورود شما به اسنپ: ۴۸۲۱"))
        assertTrue(otp("کد تایید دیجی\u200Cکالا: 73920"))
        assertTrue(otp("<#> Your WhatsApp code: 482915"))
    }

    // ---- SE-04: Arabic letter forms, kashida, zero-width characters ----

    @Test fun arabicLetterFormsAreRecognised() {
        assertTrue(otp("رمز پويا شما: 58231"))
        assertTrue(otp("كد تاييد شما: ۴۸۲۱"))
        assertTrue(otp("كد تأييد: ٤٨٢١٩"))
        assertTrue(otp("رمـــز پویا: ۹۸۷۶۵۴"))
        assertTrue(otp("کد\u200Cتایید: 1234"))
        assertTrue(otp("رمز\u200B یکبار مصرف: ۵۵۴۴۳۳"))
        assertTrue(otp("رَمز عبور: 667788"))
    }

    // ---- SE-02: links without http or a slash ----

    @Test fun bareDomainsAreLinks() {
        assertTrue(link("Your code 482913. Verify at mybank-secure.com"))
        assertTrue(link("رمز پویا ۱۲۳۴۵۶ را در sadad-pay.ir وارد کنید"))
        assertTrue(link("کد تایید: ۴۸۲۱ ورود به x.ir"))
        assertTrue(link("Claim at bit.ly now"))
        assertTrue(link("see t.co/abc"))
        assertTrue(link("go to secure-login.top."))
        assertTrue(link("visit SHAPARAK-IR.XYZ today"))
    }

    @Test fun obfuscatedLinksAreLinks() {
        assertTrue(link("Hit example[.]com now"))
        assertTrue(link("Hit example(.)com now"))
        assertTrue(link("Hit example[dot]com now"))
        assertTrue(link("hxxps://evil.example"))
        assertTrue(link("Hit exa\u200Bmple.com now"))
        assertTrue(link("panel at 185.12.33.4/login"))
    }

    @Test fun ordinaryTextIsNotALink() {
        assertFalse(link("Your code is 123456. Do not share it."))
        assertFalse(link("Price 1.50/month"))
        assertFalse(link("Mr.Smith, your code is 4821"))
        assertFalse(link("e.g. your code 4821"))
        assertFalse(link("Amount 1.200.000 ریال, code 5566"))
        assertFalse(link("Questions? support@mybank.com"))
        assertFalse(link("Hello.How are you"))
        assertFalse(link("Your code is 123456\n\n@example.com #123456"))
        assertFalse(link("رمز پویا: ۱۲۳۴۵۶. بانک ملت"))
    }
}
