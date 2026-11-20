package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step N, item 5: the eight shapes of real codes the detector missed in 0.4.14 (notification investigation, N2), each
 * read by [OtpDetector.looksLikeOtp] (shown as a code: notification, copy chip, Codes filter) and by
 * [AllowingCode.isAllowingCode] (allows the sender for good). Every text, brand and number is made up.
 *
 * | Shape                                  | Shown as a code                        | Allows the sender            |
 * |----------------------------------------|----------------------------------------|------------------------------|
 * | 1. more than four words in between     | widened: up to eight, one sentence     | kept: four words, or secret  |
 * | 2. a word with digits or 16+ letters   | widened, next to a strong keyword      | kept                         |
 * | 3. more than four marks in between     | widened: up to eight                   | kept                         |
 * | 4. no mark at all ("رمز۴۸۲۹۱۳")         | kept (copy extractor cannot see it)    | kept                         |
 * | 5. an ad word or "%" anywhere          | widened: a code phrase, ad elsewhere   | kept: never with an ad word  |
 * | 6. plain "code" with opt-out or gift   | kept                                   | kept                         |
 * | 7. 3 digits, or 9+ without "password"  | kept                                   | kept                         |
 * | 8. a keyword not in the list           | widened: پسورد, کلمه عبور, الرقم السري  | widened, same words          |
 *
 * Kept means the shape is still no code (or allows nobody), on purpose; the tests pin that too.
 */
class OtpDetectorCorpusTest {

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

    private fun assertNoCode(body: String) {
        assertFalse(body, shown(body))
        assertFalse(body, allows(body))
    }

    @Test fun shape1AStrongKeywordReachesEightWordsOfOneSentence() {
        assertShownOnly("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913", "482913")
        assertShownOnly("رمز یکبار مصرف شما برای پرداخت قبض در درگاه بانک نمونه ۴۸۲۹۱۳ است", "482913")
        assertShownOnly("Your verification code for signing up to the Example app is 482913", "482913")
        // A secret line lets the strict reading allow it anyway.
        assertShownAndAllows("Example Bank: OTP for your order at Example Shop is 448210. Never share it.", "448210")
    }

    @Test fun shape1TheWideReachIsNotForThePlainWordAYearOrTheNextSentence() {
        assertNoCode("کد شما برای ثبت نام در سامانه نمونه و دریافت خدمات 482913")
        assertNoCode("Your password will expire at the end of December 2026")
        assertNoCode("Your verification is complete. Thank you for choosing Example Bank today 4821")
    }

    @Test fun shape2AWordWithDigitsDoesNotBreakTheChainOfAStrongKeyword() {
        assertShownOnly("Your OTP from Shop24 is 482913", "482913")
        assertShownOnly("کد تایید اپلیکیشن نمونه۳۶۵ شما: ۴۸۲۹۱۳", "482913")
        assertShownOnly("Your verification code for the Examplecloudservices app is 482913", "482913")
        assertNoCode("Use code SAVE20 to get 5000 points")
    }

    /** The known cost of shape 2: a promo word after "sign in" is shown, but allows nobody. */
    @Test fun shape2APromoWordAfterAStrongKeywordIsShownButNeverAllows() {
        assertShownOnly("Sign in with SAVE20 and get 5000 points", "5000")
    }

    @Test fun shape3UpToEightMarksStandBetween() {
        assertShownOnly("OTP -- >> 482913", "482913")
        assertTrue(shown("کد تایید:\n\n\n\n۴۸۲۹۱۳"))
        assertTrue(shown("Verification code:\r\n\r\n482913"))
        assertNoCode("Your PIN for card ****1234 was changed")
    }

    /** Kept: the copy extractor needs a mark before the number ([com.glooboo.umessage.helpers.OtpCodeExtractor]). */
    @Test fun shape4AKeywordGluedToTheNumberIsStillNoCode() {
        assertNoCode("OTP482913")
        assertNoCode("رمز۴۸۲۹۱۳")
        assertNoCode("کد‌۴۸۲۹۱۳")
    }

    @Test fun shape5ACodePhraseWinsOverAnAdWordInAnotherSentence() {
        assertShownOnly("Your verification code is 482913. Get 20% off your next ride!", "482913")
        assertShownOnly("کد تایید شما ۴۸۲۹۱۳\nجشنواره پاییزه فروشگاه نمونه", "482913")
        assertShownOnly("رمز یکبار مصرف 482913\nتخفیف ۲۰٪ در خرید بعدی", "482913")
    }

    @Test fun shape5AnAdvertisementStaysNoCode() {
        listOf(
            "50% off, code SALE20",
            "Use promo code SAVE20 for 20% off your next order!",
            "You are the winner, verify with 123456",
            "Winner! Verify with 123456",
            "کد تخفیف ۴۸۲۹۱۳ برای شما",
            "کد تایید ۴۸۲۹۱۳ برای شرکت در قرعه کشی",
            "Your verification code for the prize draw is 482913",
            "کد تخفیف ۲۰٪: ۴۸۲۹۱۳\nلغو۱۱",
        ).forEach { assertNoCode(it) }
    }

    @Test fun shape6ThePlainWordWithTheOptOutLineOrAGiftWordStaysNoCode() {
        assertNoCode("کد ۴۸۲۹۱۳ را وارد کنید. لغو۱۱")
        assertNoCode("کد شما ۴۸۲۹۱۳\nلغو۱۱")
        assertNoCode("Your gift card code is 482913")
        assertNoCode("Example Rewards: your code is 482913")
        // A strong keyword wins over the opt-out line, as before (a real login code can carry it).
        assertShownAndAllows("کد ورود شما ۴۸۲۹۱۳\nلغو۱۱", "482913")
    }

    @Test fun shape7ThreeDigitsAndLongNumbersWithoutAPasswordWordStayNoCode() {
        assertNoCode("Your verification code is 482")
        assertNoCode("کد تایید شما: ۴۸۲")
        assertNoCode("Your verification code is 482913570")
        assertNoCode("Your temporary password is 9876543210")
        assertShownAndAllows("رمز عبور شما 482913570 است", "482913570")
    }

    @Test fun shape8MoreWordsForAPassword() {
        assertShownAndAllows("پسورد شما 482913 است", "482913")
        assertShownAndAllows("کلمه عبور موقت شما ۴۸۲۹۱۳ است", "482913")
        assertShownAndAllows("رقمك السري المؤقت 482915", "482915")
        assertShownAndAllows("الرقم السري لمرة واحدة 482915", "482915")
    }

    @Test fun shape8WordsLeftOutOnPurpose() {
        assertNoCode("Enter 4321 to confirm your phone number.")
        assertNoCode("Pay 2500 to confirm your booking")
        assertNoCode("Your access token is 482913")
    }

    /** Fable F-1: a CJK code word in its own clause, closer to the code than an order word, allows. */
    @Test fun cjkACodeWordInItsOwnClauseWinsOverAnOrderWord() {
        assertTrue(allows("您正在支付订单，验证码 123456"))
        assertTrue(allows("验证码 123456，用于支付订单"))
        assertFalse(allows("您的订单验证码是 573920"))
        assertFalse(allows("，您的订单验证码是 573920"))
        assertFalse(allows("【示例快递】您的取件验证码是 482915"))
        assertFalse(allows("ご注文の確認コード：664422"))
    }

    /**
     * Known gap, outside step N's files: [OtpCodes.tokens] gives no value for a CJK or Thai code word, so such a code
     * is shown but has no copy chip. When that is fixed, this test changes.
     */
    @Test fun cjkACodeIsShownWithoutACopyValue() {
        val body = "您的验证码是 482915，5分钟内有效。"
        assertTrue(shown(body))
        assertTrue(allows(body))
        assertEquals(emptyList<String>(), copied(body))
    }
}
