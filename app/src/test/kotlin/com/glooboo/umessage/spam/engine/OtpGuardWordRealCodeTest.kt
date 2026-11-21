package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step N, review 3 (R3-9): every guard and veto word of the detector and of [AllowingCode] next to a real code, so a
 * guard that grows too wide shows up here first. Each text is shown, copies the code and allows its sender. The
 * notice side of the same words is pinned in [OtpDetectorCorpusTest]. Every text and number is made up.
 */
class OtpGuardWordRealCodeTest {

    private val realCodes = listOf(
            // Strict reference words, stepped over
            "OTP for شماره کارت 6037991234 is 482913",
            "رمز پویا شماره کارت 1234: 482913",
            "رمز پویا شماره حساب 12345678: 482913",
            "کد تایید شماره پیگیری 12345678: 482913",
            "کد تایید شماره سفارش 12345: 482913",
            "کد تایید برای شماره 09121234567 برابر 482913 است",
            "رمز یکبار مصرف ارسال شده به شماره 0912: 482913",
            "کد تایید شناسه 12345: 482913",
            "رمز پویا مانده حساب 250000: 482913",
            "رمز پویا مانده 250000: 482913",
            "رمز پویا کارت منتهی به 1234: 482913",
            "OTP for tracking number 12345678 is 482913",
            "Your password reset code for account number 12345678 is 482913",
            "Your OTP for order number 12345678 is 482913",
            "OTP for reference number 12345678 is 482913",
            "Your OTP for account ending in 1234 is 482913",
            "Your OTP for account ending 1234 is 482913",
            "OTP for card ending 1234 is 482913",
            "Your OTP for A/c ending 1234 is 482913",
            "Your Example verification code for phone ending 4829 is 771234",
            "Your OTP for number ending 4829 is 482913",
            "Your OTP for mobile ending 4829 is 482913",
            "Your OTP for card ending in 1234 is 482913",
            "OTP 482913. Avl balance 5000",
            // Far words, near a keyword
            "کد تایید شماره: 482913",
            "کد تایید شعبه: 482913",
            "کد تایید شما عدد 482913 است",
            "OTP for your account is 482913",
            "Your OTP number is 482913",
            "Your mobile verification code is 482913",
            "OTP for ending your session: 482913",
            // Amount words
            "Your OTP for transaction amount 2500 is 482913",
            "رمز پویا خرید به مبلغ 1500000 ریال: 482913",
            "Use OTP 482913 for transaction amount 2500",
            "رمز پویا: 482913\nمبلغ: 1500000 ریال",
            "ثبت نام انجام شد. کد تایید: 482913",
            // Notice words with a one-time phrase
            "Your WiFi OTP is 482913",
            "رمز یکبار مصرف ورود به پنل مودم: 482913",
            "رمز التحقق لحساب نمونه هو 482913",
            "رمز التحقق لتفعيل بطاقتك هو 482913",
            "رمز التحقق رقم 482913",
            "کد تایید برای رفع مسدودی حساب: 482913",
            "رمز یکبار مصرف 482913 تا ۲ دقیقه دیگر منقضی می شود",
            "رمز التحقق 482913 ستنتهي صلاحيته خلال 5 دقائق",
            // Hard vetoes with a real code in the next sentence
            "تم تغيير رقم هاتفك. رمز التحقق الجديد: 482913",
            "Your verification code is 482913. Your card ending 1234 will be charged",
            // Masks, stars, dates, phones
            "کارت ****1234 رمز پویا 482913",
            "OTP 482913 for txn on card *1234",
            "Your OTP is 482913, valid until 2026-11-21 14:30",
            "رمز شخصی شما 1234567890. تماس با پشتیبانی 02112345678",
            "Your code is 4829\n\n@example.com #4829",
            // Ad / prize words as a footer
    )

    @Test fun aRealCodeNextToEachGuardWordIsShownCopiedAndAllowed() {
        for (body in realCodes) {
            val code = Regex("[0-9]+(?![0-9])").findAll(body).map { it.value }.toList()
            assertTrue(body, OtpDetector.looksLikeOtp(body))
            assertTrue(body, AllowingCode.isAllowingCode(body))
            assertTrue(body, OtpCodes.tokens(body).map { it.value }.single() in code)
        }
    }

    /** An advertising footer after the code's sentence: the code is shown and copied, but allows nobody. */
    @Test fun aCodeWithAnAdvertisingFooterIsShownOnly() {
        val body = "Your verification code is 482913. Get 20% off your next ride!"
        assertTrue(OtpDetector.looksLikeOtp(body))
        assertEquals(listOf("482913"), OtpCodes.tokens(body).map { it.value })
        assertFalse(AllowingCode.isAllowingCode(body))
    }
}
