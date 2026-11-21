package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.OtpCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step N, item 5: the eight shapes of real codes the detector missed in 0.4.14 (notification investigation, N2), each
 * read by [OtpDetector.looksLikeOtp] (shown as a code: notification, copy chip, Codes filter) and by
 * [AllowingCode.isAllowingCode] (allows the sender for good), plus the findings of the step N review (N5-1 to N5-14).
 * Every text, brand and number is made up.
 *
 * | Shape                                  | Shown as a code                               | Allows the sender    |
 * |----------------------------------------|-----------------------------------------------|----------------------|
 * | 1. more than four words in between     | widened: 8 words of one sentence, code phrase | kept                 |
 * | 2. a word with digits or 16+ letters   | widened, same phrases                         | kept                 |
 * | 3. more than four marks in between     | widened: up to eight                          | widened: up to eight |
 * | 4. no mark at all ("رمز۴۸۲۹۱۳")         | kept                                          | kept                 |
 * | 5. an ad word or "%" anywhere          | widened: code phrase, ad only after its sentence | kept: never       |
 * | 6. plain "code" with opt-out or gift   | kept                                          | kept                 |
 * | 7. 3 digits, or 9+ without "password"  | kept                                          | kept                 |
 * | 8. a keyword not in the list           | widened: password words                       | widened, same words  |
 *
 * The wide reach of shapes 1 and 2 starts only from a phrase of [OtpKeywords.codePhrases] or a password word
 * ([OtpKeywords.password]), never from "verify", "log in" or "تایید", and never takes a year, a date or a number right
 * after a reference word ("شماره", "شناسه", "tracking number"). Codes it finds are shown, not allowed (owner, B).
 * Near a keyword only the strict reference words count ("account number", "ending in", "شماره پیگیری"), so "OTP for
 * your account is 482913" and "کد تایید شماره: 482913" are codes.
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

    @Test fun shape1ACodePhraseReachesEightWordsOfOneSentence() {
        assertShownOnly("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913", "482913")
        assertShownOnly("رمز یکبار مصرف شما برای پرداخت قبض در درگاه بانک نمونه ۴۸۲۹۱۳ است", "482913")
        assertShownOnly("Your verification code for signing up to the Example app is 482913", "482913")
        // N5-4: a "do not share" line vouches only for a code a few words from its keyword.
        assertShownOnly("Example Bank: OTP for your order at Example Shop is 448210. Never share it.", "448210")
    }

    @Test fun shape1TheWideReachIsNotForThePlainWordAYearOrTheNextSentence() {
        assertNoCode("کد شما برای ثبت نام در سامانه نمونه و دریافت خدمات 482913")
        assertNoCode("Your password will expire at the end of December 2026")
        assertNoCode("Your verification is complete. Thank you for choosing Example Bank today 4821")
    }

    /** N5-9: bank notices, phone numbers, dates, bill IDs and tracking numbers are no code. */
    @Test fun shape1BankNoticesAndReferenceNumbersAreNoCode() {
        listOf(
            "خرید شما تایید و مبلغ از حساب کسر شد مانده 250000",
            "واریز به حساب شما تایید شد، مانده پس از واریز 12345678",
            "رمز دوم کارت شما فعال شد و مانده حساب 250000",
            "ورود به حساب کاربری شما در سامانه نمونه با شماره پیگیری 48291375 انجام شد",
            "verify your account by calling us at 5551234",
            "Sign in to view your statement for account ending in 482913",
            "Your password for user john1985 expires 20261231",
            "رمز دوم خود را برای پرداخت قبض با شناسه 12345678 وارد کنید",
            "Your one-time password request was received and your tracking number is 48291375",
            "رمز یکبار مصرف شما برای تراکنش کارت به شماره 1234567 ارسال شد",
            "رمز عبور جدید برای شماره 12345678 ثبت شد",
            "Your PIN for account 1234**** is now active",
        ).forEach { assertNoCode(it) }
    }

    @Test fun shape2AWordWithDigitsDoesNotBreakTheChainOfACodePhrase() {
        assertShownOnly("Your OTP from Shop24 is 482913", "482913")
        assertShownOnly("کد تایید اپلیکیشن نمونه۳۶۵ شما: ۴۸۲۹۱۳", "482913")
        assertShownOnly("Your verification code for the Examplecloudservices app is 482913", "482913")
        assertNoCode("Use code SAVE20 to get 5000 points")
        // N5-9: "sign in" has no wide reach, so a promo word after it breaks the chain.
        assertNoCode("Sign in with SAVE20 and get 5000 points")
    }

    /** N5-11, R2-1: shape 3 pins the strict reading and the copy value too; both read eight marks. */
    @Test fun shape3UpToEightMarksStandBetween() {
        listOf(
            "OTP -- >> 482913",
            "Verification code:\r\n\r\n482913",
            "Your verification code:\r\n\r\n482913",
            "OTP:\r\n\r\n482913",
            "Example\r\nVerification code:\r\n\r\n482913\r\nValid 5 min",
            "کد تایید:\r\n\r\n482913",
            "کد تایید:\n\n\n\n۴۸۲۹۱۳",
            "Your Example verification code is:\r\n\r\n482913\r\n\r\nDo not share it.",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R2-2: near a keyword, bare "account" and "شماره" name no reference; amounts are shown but allow nobody. */
    @Test fun nearAKeywordOnlyStrictReferenceWordsCount() {
        listOf(
            "OTP for your account is 482913",
            "Verification code for your Example account: 482913",
            "Your login code for account: 482913",
            "Your Example account: 482913 is your verification code",
            "کد تایید شماره: 482913",
            "کد تایید تغییر شماره: 482913",
            "رمز یکبار مصرف شماره 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        assertShownOnly("کد تایید شما برای برداشت مبلغ: 482913", "482913")
        assertShownOnly("Your verification code for your Example account is 482913", "482913")
        listOf(
            "پرداخت قبض شما تایید شد، شماره پیگیری 482913",
            "Your PIN for card ending 1234 is now active",
            "Your verification code was sent to the number ending 4829",
        ).forEach { assertNoCode(it) }
        // An amount is no code to allow, wherever it stands.
        assertFalse(allows("بانک نمونه\nخرید با رمز پویا\nمبلغ: 1500000\nمانده: 12345678"))
    }

    /** R2-8: the last digits of a phone, a mobile or a branch are no code. */
    @Test fun theLastDigitsOfAPhoneAreNoCode() {
        listOf(
            "Your OTP was sent to your phone ending 4829",
            "Your one-time password has been sent to mobile 4829137",
            "کد تایید به تلفن همراه منتهی به 4829 ارسال شد",
            "رمز عبور جدید به ایمیل شما ارسال شد شعبه 4829",
        ).forEach { assertNoCode(it) }
    }

    /** R2-4: Thai, Lao and Burmese write a clause without spaces; a code after it is still found. */
    @Test fun aCodeAfterALongThaiLaoOrBurmeseClauseIsFound() {
        listOf(
            "OTP สำหรับเข้าสู่ระบบของคุณคือ 482913",
            "OTP ของคุณสำหรับการเข้าสู่ระบบแอปตัวอย่างคือ 482913",
            "OTP ຂອງທ່ານສຳລັບການເຂົ້າສູ່ລະບົບແມ່ນ 482913",
            "OTP သင့်အကောင့်သို့ဝင်ရောက်ရန်အတွက် 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R2-6: an emoji with a variation selector glued to the code does not hide it. */
    @Test fun anEmojiGluedToTheCodeDoesNotHideIt() {
        listOf("Your OTP is:➡️482913", "کد تایید ✅️482913", "OTP❗️482913", "Your code 👉️ 482913")
            .forEach { assertShownAndAllows(it, "482913") }
    }

    /** R2-3: the words of an advertisement or a prize count after the first 2000 characters too. */
    @Test fun anAdAfterTheFirstTwoThousandCharactersStillCounts() {
        val filler = " Thank you for choosing Example. We are glad to have you with us today.".repeat(30)
        assertShownOnly("Your verification code is 482913.$filler Get 50% off at example.com/win", "482913")
        assertFalse(allows("Your code is 482913.$filler promo code SAVE20"))
        assertNoCode("Your verification code is 482913.$filler Winner! Claim your prize now")
        assertFalse(allows("کد تایید شما 482913 است.$filler تخفیف ۵۰٪ ویژه شما"))
        assertFalse(OtpDetector.noAdBeforeCode("Your verification code is 482913.$filler Winner! Claim your prize"))
    }

    /** Kept: the copy extractor needs a mark before the number ([com.glooboo.umessage.helpers.OtpCodeExtractor]). */
    @Test fun shape4AKeywordGluedToTheNumberIsStillNoCode() {
        assertNoCode("OTP482913")
        assertNoCode("رمز۴۸۲۹۱۳")
        assertNoCode("کد\u200C۴۸۲۹۱۳")
    }

    @Test fun shape5ACodePhraseWinsOverAnAdvertisingFooter() {
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
            // The ad word in the code's own sentence is no footer.
            "Your OTP is 482913, enjoy 20% off",
        ).forEach { assertNoCode(it) }
    }

    /** N5-7: an ad word before the code, and a prize word anywhere, make it an advertisement. */
    @Test fun shape5AnAdBeforeTheCodeOrAPrizeWordIsNoFooter() {
        listOf(
            "شما برنده قرعه کشی شدید. کد تایید: 482913. برای دریافت جایزه به example.com مراجعه کنید",
            "Congratulations winner! Your OTP is 482913. Send it to 30001234 to claim your prize",
            "تخفیف ۵۰٪ نمونه! کد تایید شما برای دریافت: 482913",
        ).forEach { assertNoCode(it) }
    }

    /** N5-8: a dot inside a link or a version does not end the sentence. */
    @Test fun shape5ADotInALinkDoesNotEndTheSentence() {
        assertNoCode("کد تایید ۴۸۲۹۱۳ در example.com با تخفیف ۵۰٪")
        assertNoCode("Your verification code 482913 at shop.example.com gets you 20% off")
        assertNoCode("OTP 482913 v2.0 promo winner")
    }

    /** N5-7: what the old filter (flag off) asks before it lets a code through Smart mode. */
    @Test fun shape5TheOldFilterSeesNoAdBeforeTheCode() {
        assertTrue(OtpDetector.noAdBeforeCode("Your verification code is 482913. Get 20% off your next ride!"))
        assertTrue(OtpDetector.noAdBeforeCode("Your OTP is 482913"))
        assertFalse(OtpDetector.noAdBeforeCode("Winner! password: 12345678"))
        assertFalse(OtpDetector.noAdBeforeCode("Congratulations winner! Your OTP is 482913. Claim your prize"))
        assertFalse(OtpDetector.noAdBeforeCode("تخفیف ۵۰٪ نمونه! کد تایید شما برای دریافت: 482913"))
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
        // N5-12: with a ZWNJ and ی, and two more Arabic words.
        assertShownAndAllows("کلمه\u200Cی عبور شما: ۴۸۲۹۱۳", "482913")
        assertShownAndAllows("كلمة السر الخاصة بك 482915", "482915")
        assertShownAndAllows("الرمز السري 482915", "482915")
    }

    @Test fun shape8WordsLeftOutOnPurpose() {
        assertNoCode("Enter 4321 to confirm your phone number.")
        assertNoCode("Pay 2500 to confirm your booking")
        assertNoCode("Your access token is 482913")
    }

    /** N5-6, R2-5: a card notice or a changed password is no reason to allow, in Arabic and Persian too. */
    @Test fun cardNoticesAndChangedPasswordsDoNotAllow() {
        listOf(
            "تم تغيير الرقم السري لبطاقتك المنتهية بـ 1234",
            "تم تغيير الرقم السري لبطاقتك رقم 1234",
            "تم تغيير كلمة المرور لحسابك رقم 482913",
            "الرقم السري لبطاقة الصراف 1234 تم تفعيله",
            "الرقم السري لبطاقة رقم 1234 هو 5678",
            "تم إعادة تعيين الرقم السري لبطاقتكم رقم 1234",
            "لقد تم إيقاف الرقم السري للبطاقة 1234",
            "تم انشاء الرقم السري لبطاقة مدى 1234",
            "الرقم السري لحساب 482913 تم تحديثه",
            "تم تحديث كلمة السر لحسابك رقم 482913",
            "رمز عبور حساب 482913 مسدود شد",
            "كلمة السر الخاصة بحسابك 482913 ستنتهي قريبا",
        ).forEach { assertFalse(it, allows(it)) }
        assertNoCode("رمز عبور شما در تاریخ ۱۴۰۵/۰۸/۲۷ تغییر کرد")
        assertNoCode("Password changed on 2026-11-18 at 1432")
        // The Persian word for "change" alone is no veto, and "your account" in Arabic is none either.
        assertShownAndAllows("کد تایید تغییر شماره موبایل: 482913", "482913")
        assertShownAndAllows("٧٣٩٢٠١ هو رمز الدخول لحسابك", "739201")
    }

    /** Second review gains checked: a wi-fi, modem or expired password allows nobody; a phone number is no code. */
    @Test fun passwordsThatAreNoOneTimeCodeAllowNobody() {
        listOf(
            "پسورد وای فای 48291375",
            "پسورد مودم 48291375",
            "كلمة السر لشبكة الواي فاي 48291375",
            "کلمه عبور حساب 482913 منقضی شده است",
        ).forEach { assertFalse(it, allows(it)) }
        assertNoCode("برای دریافت کلمه عبور با 9123456789 تماس بگیرید")
        assertNoCode("پسورد را به شماره 9123456789 ارسال کنید")
        assertNoCode("برای دریافت پسورد عدد 4829 را به 30001234 ارسال کنید")
        assertShownAndAllows("رمز عبور شما 9123456789 است", "9123456789")
    }

    /** N5-3, R2-7: a code between stars is a code, with the digits as its copy value; only a mask is none. */
    @Test fun aCodeBetweenStarsIsStillACode() {
        listOf(
            "Your verification code is *482913*",
            "Your Example code is *482913*. Don't share it.",
            "رمز پویا *482913*",
            "*482913* کد تایید شما در نمونه",
            "کد تایید شما: **482913**",
            "OTP *482913",
            "OTP: *482913*",
            "OTP ***482913***",
        ).forEach { assertShownAndAllows(it, "482913") }
        // The last four digits of a card after one star, and a dial code, are no code.
        assertNoCode("خرید با رمز پویا از کارت *1234 انجام شد")
        assertNoCode("PIN *1234# to activate")
    }

    /** N5-14: a labelled value between stars is copied without them. */
    @Test fun aLabelledValueBetweenStarsIsCopiedWithoutThem() {
        assertEquals(listOf("4829"), copied("کد: *4829*"))
        assertEquals(listOf("482913"), copied("OTP: *482913*"))
    }

    /** N5-10 and N5-4: a masked card or account number never gives a code, nor allows with a "never share" line. */
    @Test fun aMaskedNumberIsNoCode() {
        listOf(
            "Your PIN for card ****1234 was changed",
            "Your PIN for card **** 1234 is now active",
            "Your PIN for card ••••1234 is now active",
            "Your PIN for card #### 1234 is now active",
            "رمز کارت **** ۱۲۳۴ فعال شد",
            "Your PIN for card **** 1234 is now active. Never share your PIN.",
            "verify your account by calling us at 5551234. Do not share this message",
        ).forEach { assertNoCode(it) }
    }

    /** Fable F-1: a CJK code word in its own clause wins over an order word, but only for paying or logging in. */
    @Test fun cjkACodeWordInAPayClauseWinsOverAnOrderWord() {
        assertTrue(allows("您正在支付订单，验证码 123456"))
        assertTrue(allows("验证码 123456，用于支付订单"))
        listOf(
            "您的订单验证码是 573920",
            "，您的订单验证码是 573920",
            "【示例快递】您的取件验证码是 482915",
            "ご注文の確認コード：664422",
            // N5-5: pick-up, shipped, cancelled.
            "您的快递已到，取件验证码 482915",
            "【示例驿站】您的快递已到，取件验证码 482915，请及时领取",
            "您的订单已发货，验证码 482915",
            "订单已取消。验证码 482915",
            "주문이 완료되었습니다, 인증번호 482915",
        ).forEach { assertFalse(it, allows(it)) }
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

    /** One full check: shown, allows, copy values. */
    private fun check(body: String) {
        shown(body)
        allows(body)
        copied(body)
    }

    /**
     * N5-1, N5-2: texts that made the patterns backtrack for seconds (a mark fitted both a word and a separator). The
     * first check warms the classes up; the second must finish fast. The old patterns hang here, hence the timeout.
     */
    private fun assertFast(body: String) {
        check("Your OTP is 482913")
        check(body)
        val start = System.nanoTime()
        check(body)
        val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
        assertTrue("$millis ms", millis < FAST_MILLIS)
    }

    /** Every timing text holds a number, so the patterns are read, not skipped by the "no digit" rule. */
    @Test(timeout = TIMEOUT_MILLIS) fun marksOnlyAfterAKeywordOrACodeAreFast() {
        assertFast("OTP " + "\u0301".repeat(200) + " 482913")
        assertFast("482913 " + "\u0301".repeat(200))
        assertFast("کد تایید " + ("\u0610".repeat(6) + " ").repeat(6) + "482913")
    }

    @Test(timeout = TIMEOUT_MILLIS) fun thaiAfterAKeywordOrACodeIsFast() {
        val thai = "สวัสดีครับขอบคุณที่ใช้บริการของเราที่นี่ ".repeat(10).take(300)
        assertFast("OTP $thai 482913")
        assertFast("482913 $thai")
    }

    /** R2-9: a long text with many codes and an advertising word is bounded by the one cut text. */
    @Test(timeout = TIMEOUT_MILLIS) fun manyCodesWithAnAdAreFast() {
        assertFast("Your verification code is 482913. ".repeat(150) + "promo")
    }

    @Test(timeout = TIMEOUT_MILLIS) fun aLongHindiTextIsFast() {
        val hindi = "आपका खाता सत्यापन के लिए तैयार है कृपया प्रतीक्षा करें ".repeat(40).take(1700)
        assertFast("$hindi 12")
        assertFast("सत्यापन कोड 482913 $hindi")
    }

    @Test(timeout = TIMEOUT_MILLIS) fun accentedLettersAfterAKeywordAreFast() {
        assertFast("OTP " + "e\u0301".repeat(24) + " 482913")
        assertFast("OTP " + "e\u0301".repeat(30) + " 482913")
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val FAST_MILLIS = 50L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
