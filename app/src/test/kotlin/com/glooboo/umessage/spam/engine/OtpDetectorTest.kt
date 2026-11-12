package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpDetectorTest {

    private fun otp(body: String) = OtpDetector.looksLikeOtp(body)

    @Test fun englishOtpForms() {
        assertTrue(otp("Your OTP is 482915"))
        assertTrue(otp("123456 is your login code"))
        assertTrue(otp("Verification code: 1234567"))
        assertTrue(otp("G-482915 is your Google verification code."))
        assertTrue(otp("Your one-time password for login is 4821"))
        assertTrue(otp("PIN 9087"))
    }

    @Test fun fourToEightDigitsOnly() {
        assertTrue(otp("Your code is 1234"))
        assertTrue(otp("Your code is 12345678"))
        assertFalse(otp("Your code is 123"))
        assertFalse(otp("Your code is 123456789"))
    }

    @Test fun persianDigitsAndKeywords() {
        assertTrue(otp("رمز پویا: ۱۲۳۴۵"))
        assertTrue(otp("کد تایید شما ۴۸۲۱ می باشد"))
        assertTrue(otp("رمز یکبار مصرف شما 739201"))
        assertTrue(otp("کدتان: ۵۵۶۶۷۷"))
    }

    @Test fun arabicIndicDigitsAndKeywords() {
        assertTrue(otp("رمز التحقق الخاص بك هو ٤٨٢٩١٥"))
        assertTrue(otp("كود ٧٧٨٨"))
    }

    @Test fun aGroupedNumberWithoutACodeWordIsNotACode() {
        assertFalse(otp("123-456"))
    }

    @Test fun groupedCodesAreCodes() {
        assertTrue(otp("Your code: 123-456"))
        assertTrue(otp("123 456 is your Instagram code"))
        assertTrue(otp("Your WhatsApp code: 123-456"))
        assertTrue(otp("کد تایید شما: ۱۲۳-۴۵۶"))
        assertTrue(otp("رمز التحقق ٤٨٢ ٩١٣"))
        assertTrue(otp("Verification code 1234 5678"))
    }

    @Test fun regularCodesKeepPassing() {
        assertTrue(otp("Your code is 1234"))
        assertTrue(otp("OTP 48291"))
        assertTrue(otp("Your code is 123456"))
        assertTrue(otp("Your OTP is 12345678"))
        assertTrue(otp("کد تایید شما: ۱۲۳۴۵۶"))
        assertTrue(otp("Your verification code is 482913 and expires in 10 minutes"))
        assertTrue(otp("Verification code 482913. Call 0800 123 4567 if this wasn't you"))
        assertTrue(otp("کد تایید ۴۸۲۹۱۳. اگر شما نبودید تماس بگیرید ۰۲۱ ۱۲۳۴ ۵۶۷۸"))
    }

    @Test fun phoneNumbersAreNotCodes() {
        assertFalse(otp("verify your account, call 0800 123 4567"))
        assertFalse(otp("Call +1 555 010 1234 to verify"))
        assertFalse(otp("Verify your account: +441234567890"))
        assertFalse(otp("Please verify, call 0800123456"))
        assertFalse(otp("Verify your account. Hotline: 1234 5678 9012"))
        assertFalse(otp("Verify your account, call 12345678"))
        assertFalse(otp("تایید حساب: تماس بگیرید ۰۲۱ ۱۲۳۴ ۵۶۷۸"))
        assertFalse(otp("تایید حساب، تماس ۱۲۳۴۵۶"))
    }

    @Test fun keywordsNeedWordEdges() {
        assertFalse(otp("Your shipping update for order 2026 is ready"))
        assertFalse(otp("Scan the barcode 12345678 at the store"))
        // "کدام" (which) starts with "کد" but is another word.
        assertFalse(otp("کدام 1234"))
    }

    @Test fun codeMustBeNearKeyword() {
        assertFalse(otp("Use code SAVE20, call 12345678"))
        assertFalse(otp("Security alert on your account, your balance is now 250000"))
        assertFalse(otp("Your code will arrive soon. Meanwhile visit our store at the mall downtown on 4th street 12345"))
    }

    @Test fun plainNumbersAreNotOtp() {
        assertFalse(otp("Call 123456789 now for prizes"))
        assertFalse(otp("Meet at 1830"))
    }

    @Test fun alphanumericCodesNextToAKeywordAreCodes() {
        assertTrue(otp("Your code is ab12cd"))
        assertTrue(otp("Your verification code is X7K2M9"))
        assertTrue(otp("ab12cd is your login code"))
        assertTrue(otp("Use 12ab34 to verify your account"))
        assertTrue(otp("Your OTP is a1b2c3"))
        assertTrue(otp("Your code is ab12cd. It expires in 5 minutes"))
        assertTrue(otp("رمز ورود: ab12cd"))
    }

    @Test fun alphanumericTokensWithoutAKeywordAreNotCodes() {
        assertFalse(otp("ab12cd"))
        assertFalse(otp("Your order ab12cd has shipped"))
        assertFalse(otp("Meet me at gate a1b2c3"))
        assertFalse(otp("Tracking id: 1z2y3x4w"))
    }

    @Test fun theKeywordMustShareTheSentenceWithAnAlphanumericCode() {
        assertFalse(otp("Order a1b2c3. Verify your email address in the app"))
        assertFalse(otp("Verify your email address.\nOrder a1b2c3 is on its way"))
    }

    @Test fun promoAndShapeVetoesApplyToAlphanumericCodes() {
        assertFalse(otp("Promo code ab12cd"))
        assertFalse(otp("Use code SAVE20 at checkout"))
        assertFalse(otp("Reward code ab12cd for you"))
        assertFalse(otp("Your code will be sent at 10h30"))
        assertFalse(otp("Verify by 10am tomorrow, code expires 5pm"))
        assertFalse(otp("Verify your 3rd attempt, code for mp3s"))
        assertFalse(otp("Your code is abcdef"))
        assertFalse(otp("Your code is ab1c"))
        assertFalse(otp("Your code is ab12cd34ef"))
        assertFalse(otp("Verify your login at example.com/ab12cd"))
        assertFalse(otp("Verify your login, write to ab12cd@example.com"))
    }

    @Test fun anAlphanumericCodeKeepsTheRulesOfTheGenericWord() {
        assertTrue(otp("Reward OTP ab12cd"))
        assertFalse(otp("Offer code ab12cd"))
    }

    @Test fun normalizeDigitsMapsAllDecimalScripts() {
        assertEquals("12345 and 6789", OtpDetector.normalizeDigits("۱۲۳۴۵ and ٦٧٨٩"))
        assertEquals("plain 42", OtpDetector.normalizeDigits("plain 42"))
    }

    @Test fun urlDetection() {
        assertTrue(OtpDetector.containsUrl("Claim at https://example.test/claim"))
        assertTrue(OtpDetector.containsUrl("go to www.example.com now"))
        assertTrue(OtpDetector.containsUrl("pay at example.test/pay"))
        assertTrue(OtpDetector.containsUrl("see bit.ly/abc"))
        assertFalse(OtpDetector.containsUrl("Your code is 123456. @example.com #123456"))
        assertFalse(OtpDetector.containsUrl("Price 1.50/month"))
    }

    @Test fun originBoundLastLine() {
        assertTrue(OtpDetector.isOriginBound("Your code is 123456\n\n@example.com #123456"))
        assertTrue(OtpDetector.isOriginBound("Code 123456\r\n@www.example.co.uk #A1B2C3\r\n"))
        assertTrue(OtpDetector.isOriginBound("Code 123456\n@top.example #123456 @iframe.example"))
        assertTrue(OtpDetector.isOriginBound("کد: ۱۲۳۴۵۶\n@example.ir #۱۲۳۴۵۶"))
        assertFalse(OtpDetector.isOriginBound("@example.com #123456\nYour code is 123456"))
        assertFalse(OtpDetector.isOriginBound("Your code is 123456 @example.com #123456"))
        assertFalse(OtpDetector.isOriginBound("Code 123456\n@https://example.com #123456"))
        assertFalse(OtpDetector.isOriginBound("Code 123456\n@example.com/login #123456"))
        assertFalse(OtpDetector.isOriginBound("Code 123456\n@localhost #123456"))
    }

    @Test fun originBoundLineIsNotALink() {
        assertFalse(OtpDetector.containsUrl("Your code is 123456\n\n@www.example.com #123456"))
        assertTrue(OtpDetector.containsUrl("Code 123456, see https://evil.test\n@example.com #123456"))
        assertTrue(OtpDetector.containsUrl("Code 123456\n@www.example.com #123456\nhttps://evil.test"))
    }
}
