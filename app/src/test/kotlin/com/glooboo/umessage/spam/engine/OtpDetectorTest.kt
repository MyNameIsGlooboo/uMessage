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
