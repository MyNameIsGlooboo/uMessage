package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpGroupedCodeTest {

    private fun otp(body: String) = OtpDetector.looksLikeOtp(body)

    @Test fun hyphenatedAndSpacedSixDigitCodes() {
        assertTrue(otp("Your WhatsApp code: 123-456"))
        assertTrue(otp("Your verification code is 123 456"))
        assertTrue(otp("123 456 is your login code"))
    }

    @Test fun phoneNumbersAreNotCodes() {
        assertFalse(otp("Call 555-123-456 to verify"))
        assertFalse(otp("Call 1 555 123 456 7 to verify"))
    }

    @Test fun groupedAmountsAreNotCodes() {
        assertFalse(otp("Your password costs 123 456 rials"))
        assertFalse(otp("Promo code 123-456, 20% off"))
    }

    @Test fun plainCodesStillWork() {
        assertTrue(otp("Your OTP is 4821"))
        assertTrue(otp("otp 1234 5678"))
    }
}
