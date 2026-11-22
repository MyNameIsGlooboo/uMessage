package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 8 of the OTP detector (R8-1 to R8-7, and the tests R8-9 asked for). Read by [OtpDetector.looksLikeOtp]
 * (shown as a code) and [AllowingCode.isAllowingCode] (allows the sender for good). Every text, brand and number is
 * made up.
 */
class OtpDetectorReviewEightTest {

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

    /** R8-1: a request to staff, support or a team that names the code without an article is still a request. */
    @Test fun aRequestWithoutAnArticleIsARequest() {
        listOf(
            "Your OTP is 482913. Share OTP with our support team",
            "Your OTP is 482913. Share code with our staff",
            "Your OTP is 482913. Send OTP to support to verify your account",
            "Your OTP is 482913. Provide OTP to our team",
            "Your OTP is 482913. Give code to staff",
        ).forEach { assertShownOnly(it, "482913") }
        listOf(
            "Your OTP is 482913. Never share OTP with bank staff",
            "Your OTP is 482913. Do not share OTP with our staff or anyone",
            "Your OTP is 482913. Please never share code with support",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R8-2, lead 22 Nov: "code for <name>" is no access code; only "code for the gate" (an article) is one. */
    @Test fun aCodeForANameIsNoAccessCode() {
        listOf(
            "Your verification code for Gate is 482913",
            "Verification code for Gate account: 482913",
            "Your login code for Gate is 482913",
            "Your password for Gate is 482913",
            "Your verification code at Gate is 482913",
            "Your verification code for Locker is 482913",
            "Your verification code for Building Supplies is 482913",
            "Your verification code for Gate City Bank is 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        assertShownOnly("Your one-time code for the building entrance is 482913", "482913")
    }

    /** R8-3: a warning word inside a request ("someone", "fraudsters", «کلاهبرداری», a repeated code) is no warning. */
    @Test fun aWarningWordInsideARequestIsNoWarning() {
        listOf(
            "Someone tried to use your card please share the OTP with our officer 482913",
            "Someone will call you now please share the OTP with our executive. OTP 482913",
            "To stop anyone from using your card share the OTP with our officer. OTP 482913",
            "To protect you from fraudsters share the OTP with our agent. OTP 482913",
            "Scammers are targeting your account share the code with our agent to secure it. OTP 482913",
            "Your account is unsafe share the OTP with our officer to secure it. OTP 482913",
            "Beware your account is at risk share the OTP with our officer now. OTP 482913",
            "برای جلوگیری از کلاهبرداری کد تایید 482913 را به کارشناس اعلام کنید",
            "به هیچ وجه نگران نباشید و کد تایید 482913 را به کارشناس اعلام کنید",
            "حساب شما به هیچ عنوان مسدود نمی شود کد تایید 482913 را به کارشناس ما اعلام کنید",
            "حساب شما نباید مسدود شود کد تایید 482913 را به کارشناس اعلام کنید",
            "اگر پرداخت نمی کنید کد تایید 482913 را به کارشناس اعلام کنید",
        ).forEach { assertShownOnly(it, "482913") }
    }

    /** R8-4: "note", "notification", "nothing", "normal" only begin like a negation; they are no warning. */
    @Test fun aWordThatBeginsLikeANegationIsNoWarning() {
        listOf(
            "Kindly note share the OTP with our executive to complete KYC. OTP 482913",
            "Please note that you must share the code with our agent. OTP 482913",
            "As per bank notification share the OTP with our agent 482913",
            "To get notified share the OTP with our agent. OTP 482913",
            "Nothing to worry share the OTP with our officer. OTP 482913",
            "Nevertheless share the OTP with our agent. OTP 482913",
            "To return to normal share the OTP with our agent. OTP 482913",
            "Our northern branch will call share the OTP with the caller. OTP 482913",
        ).forEach { assertShownOnly(it, "482913") }
        listOf(
            "Your OTP is 482913. Note: never share it with our staff",
            "Your OTP is 482913. Please note that we do not ask you to share it with the agent",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R8-5: who is calling, an adverb or the plural «های» may stand before the Persian verb of a request. */
    @Test fun aPersianRequestWithAnAdverbIsARequest() {
        listOf(
            "کد تایید 482913 را به کارشناس تماس گیرنده اعلام کنید",
            "کد تایید 482913 را به اپراتور تماس گیرنده بگویید",
            "کد تایید 482913 را به کارشناس پشت خط اعلام کنید",
            "کد تایید 482913 را به کارشناس سریعا اعلام کنید",
            "کد تایید 482913 را به کارشناس ما فورا اعلام کنید",
            "کد تایید 482913 را به اپراتور حتما اعلام کنید",
            "کد تایید 482913 را به اپراتور لطفا بگویید",
            "کد تایید 482913 را به نماینده های ما اعلام کنید",
            "کد تایید 482913 را به کارشناس‌های ما اعلام کنید",
        ).forEach { assertShownOnly(it, "482913") }
    }

    /** R8-6: a football, sports or match forecast is betting; a weather forecast is not. */
    @Test fun aSportsForecastIsBetting() {
        listOf(
            "کد تایید پیش بینی فوتبال نمونه: 482913",
            "کد تایید شما در پیش بینی ورزشی نمونه: 482913",
            "کد تایید اپلیکیشن پیش بینی مسابقات نمونه 482913",
        ).forEach { assertShownOnly(it, "482913") }
        assertShownAndAllows("کد تایید سامانه پیش بینی وضع هوا: 482913", "482913")
    }

    /** R8-7: a link right after a hyphen is a link; a hyphen inside a name still joins its labels. */
    @Test fun aLinkAfterAHyphenIsALink() {
        listOf(
            "Your verification code for signing up to the Example app is 482913 -example.com",
            "Your verification code for signing up to the Example app is 482913 -.example.com",
            "Your verification code for signing up to the Example app is 482913 -[.]example.com",
            "Your code is 482913 -example.com",
            "Your code is 482913 -.example.com",
            "Your code is 482913 -[.]example.com",
        ).forEach { assertShownOnly(it, "482913") }
        assertTrue(OtpDetector.containsUrl("Visit -example.com"))
        assertTrue(OtpDetector.containsUrl("Visit -[.]example.com"))
        assertTrue(OtpDetector.containsUrl("Visit x-.example.com"))
        assertTrue(OtpDetector.containsUrl("Visit a-b.example.com"))
    }

    /** R8-9: the side effects of the R7-4 and R7-8 fixes, and real warnings with an if-clause. */
    @Test fun sideEffectsOfReviewSeven() {
        listOf(
            "Don't wait - share the code with our agent now. OTP 482913",
            "Your OTP is 482913. Give this code to staff at the entrance",
        ).forEach { assertShownOnly(it, "482913") }
        listOf(
            "Your OTP is 482913. If you did not request it do not share it with the caller",
            "Your OTP is 482913. If you didn't request this, don't share it with our agent",
        ).forEach { assertShownAndAllows(it, "482913") }
    }
}
