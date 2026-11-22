package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 7 of the OTP detector (R7-1 to R7-8), moved out of [OtpDetectorCorpusTest] to keep each class small. Read
 * by [OtpDetector.looksLikeOtp] (shown as a code) and [AllowingCode.isAllowingCode] (allows the sender for good).
 * Every text, brand and number is made up.
 */
class OtpDetectorReviewSevenTest {

    private fun shown(body: String) = OtpDetector.looksLikeOtp(body)

    private fun allows(body: String) = AllowingCode.isAllowingCode(body)

    private fun copied(body: String) = OtpCodes.tokens(body).map { it.value }

    /** Shown with [code] as its copy value, but the sender is not allowed: a weak shape stays silent (B, 20 Nov). */
    private fun assertShownOnly(body: String, code: String) {
        assertTrue(body, shown(body))
        assertEquals(body, listOf(code), copied(body))
        assertFalse(body, allows(body))
    }

    private fun assertShownAndAllows(body: String, code: String) {
        assertTrue(body, shown(body))
        assertEquals(body, listOf(code), copied(body))
        assertTrue(body, allows(body))
    }

    /** Lead, 22 Nov (R7-1): only a compound names an access code; "to the door" and "that the door" are no escape. */
    @Test fun anAccessCodeIsACompound() {
        listOf(
            "Access code to the door: 4829",
            "Security code to the front door: 4829",
            "PIN to the door is 4829",
            "Your access code at the door is 4829",
            "Note that the door PIN is 4829",
            "Next to the door PIN 4829",
            "کد ورود به ساختمان 4829",
            "کد امنیتی درب ورودی: 4829",
        ).forEach { assertShownOnly(it, "4829") }
    }

    /** R7-2: a Persian footer that names support is no request; only receiver words stand before the verb. */
    @Test fun aPersianSupportFooterIsNoRequest() {
        listOf(
            "کد تایید شما: 482913 در صورت بروز مشکل به پشتیبانی پیام بدهید",
            "کد تایید شما: 482913 برای پشتیبانی تیکت ارسال کنید",
            "کد تایید شما: 482913 در صورت مشکل به پشتیبانی ایمیل بفرستید",
            "مدارک خود را به نماینده تحویل بدهید. کد تایید 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        listOf(
            "کد تایید 482913 را به کارشناس ما اعلام کنید",
            "کد تایید 482913 را به کارشناس بانک اعلام کنید",
            "کد تایید 482913 را به کارشناس مربوطه اعلام کنید",
        ).forEach { assertShownOnly(it, "482913") }
    }

    /** R7-3: warnings in other wordings, also naming a caller, staff or support, are no request. */
    @Test fun aWarningInOtherWordsIsNoRequest() {
        listOf(
            "Your OTP is 482913. Scammers may ask you to share this code with support",
            "Your OTP is 482913. Under no circumstances share it with our support team",
            "Your OTP is 482913. Neither share it with our staff nor with anyone else",
            "Your OTP is 482913. We will never call you or send you a message asking you to share this code " +
                "with our staff",
            "Your OTP is 482913. Example will never contact you by phone or email to ask you to share it " +
                "with our staff",
            "Your OTP is 482913. Fraudsters may ask you to share this OTP with the caller",
            "Your OTP is 482913. If anyone asks you to give the code to the caller, hang up",
            "Your OTP is 482913. It is unsafe to share it with the caller",
            "کد تایید شما 482913 است. این کد را نباید به کارشناس بگویید",
            "کد تایید شما 482913 است. مراقب باشید کلاهبرداران از شما می خواهند کد را به اپراتور بگویید",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R7-4: a "not" inside an if-clause or an urge is no warning, and plural receivers count. */
    @Test fun aConditionOrAnUrgeIsNoWarning() {
        listOf(
            "If you do not share the code with our agent your account will be blocked. OTP 482913",
            "If you don't share the OTP with our executive your account will be suspended. OTP 482913",
            "Your OTP is 482913. If you didn't request this give the code to the caller",
            "Do not delay share the OTP with our officer to avoid account block 482913",
            "Please share the OTP with our agents now 482913",
            "کد تایید 482913 را به کارشناسان ما اعلام کنید",
            "کد تایید 482913 را به اپراتورها اعلام کنید",
        ).forEach { assertShownOnly(it, "482913") }
    }

    /** R7-5: a link whose first label stands right after a bracketed dot is still a link. */
    @Test fun aLinkAfterABracketedDotIsALink() {
        listOf(
            "Your verification code for signing up to the Example app is 482913 [.]example.com",
            "Your verification code for signing up to the Example app is 482913 تست[.]example.com",
            "Your verification code for signing up to the Example app is 482913 بانک(dot)example(dot)com",
            "Your code is 482913 [.]example.com",
        ).forEach { assertShownOnly(it, "482913") }
        assertTrue(OtpDetector.containsUrl("Visit [.]example.com"))
        assertTrue(OtpDetector.containsUrl("تست[.]example.com"))
    }

    /** R7-6, R7-7, R7-8: a post officer is a courier, a forecast is no bet, and a team of your own is no receiver. */
    @Test fun everydayWordsOfReviewSeven() {
        listOf(
            "کد تایید تحویل 482913 را به مامور پست بگویید",
            "کد تایید سامانه پیش بینی وضع هوا: 482913",
            "کد تایید سامانه پیش‌بینی وضع هوا: 482913",
            "کد تایید شما 482913 است. پیش بینی نشده",
            "Your OTP is 482913. If you did not request this, forward to our support team",
            "Your OTP is 482913. Send to support if you did not request it",
            "Your Example workspace code is 482913. Share it with your team",
        ).forEach { assertShownAndAllows(it, "482913") }
        listOf(
            "کد تایید شما در سایت پیش‌بینی نمونه 482913",
            "Your OTP is 482913. Share the code with our support team",
            "Your OTP is 482913. Share it with our staff",
        ).forEach { assertShownOnly(it, "482913") }
    }
}
