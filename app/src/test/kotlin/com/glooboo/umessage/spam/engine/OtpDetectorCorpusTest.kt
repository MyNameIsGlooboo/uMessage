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
 * | 1. more than four words in between     | widened: 8 words of one sentence, code phrase | widened, code phrase |
 * | 2. a word with digits or 16+ letters   | widened, same phrases                         | widened, code phrase |
 * | 3. more than four marks in between     | widened: up to eight                          | widened: up to eight |
 * | 4. no mark at all ("رمز۴۸۲۹۱۳")         | kept                                          | kept                 |
 * | 5. an ad word or "%" anywhere          | widened: code phrase, ad only after its sentence | kept: never       |
 * | 6. plain "code" with opt-out or gift   | kept                                          | kept                 |
 * | 7. 3 digits, or 9+ without "password"  | kept                                          | kept                 |
 * | 8. a keyword not in the list           | widened: password words                       | widened, same words  |
 *
 * The wide reach of shapes 1 and 2 starts only from a phrase of [OtpKeywords.codePhrases] or a password word
 * ([OtpKeywords.password]), never from "verify", "log in" or "تایید", and never takes a year, a date or a number right
 * after a reference word ("شماره", "account", "tracking number"). Codes it finds from a phrase of
 * [OtpKeywords.codePhrases] allow their sender too, in a text without a link and with no veto word between (owner,
 * 21 Nov); from a password word they are shown only.
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

    /** Owner, 21 Nov: a code phrase eight words away in the same sentence allows too, without a link. */
    @Test fun shape1ACodePhraseReachesEightWordsOfOneSentence() {
        listOf(
            "کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913",
            "رمز یکبار مصرف شما برای پرداخت قبض در درگاه بانک نمونه ۴۸۲۹۱۳ است",
            "Your verification code for signing up to the Example app is 482913",
            "Example Bank: OTP for your order at Example Shop is 448210. Never share it.",
            "رمز التحقق الخاص بك لتسجيل الدخول إلى تطبيق نمونه هو 482913",
        ).forEach { assertTrue(it, shown(it) && allows(it)) }
    }

    /**
     * Review 5: the wide allow starts only from a one-time code phrase before the code (R5-3, R5-4), with no comma
     * and no veto word between (also five to eight words back), and no link anywhere in the text (R5-6).
     */
    @Test fun theWideAllowIsOnlyForAOneTimePhraseBeforeTheCode() {
        listOf(
            // R5-3: a lasting secret ("رمز عبور", "رمز ورود", "passcode") six to eight words away
            "رمز عبور جدید حساب کاربری شما در سامانه نمونه 48291375 می باشد",
            "رمز عبور وای فای مهمان هتل نمونه برای اتاق شما 48291375",
            "رمز ورود درب ساختمان برای پیک و مهمان این ماه 4829",
            "Your passcode for the building entrance door this month is 4829",
            "Meeting passcode for the Example webinar on Friday at noon 482913",
            // R5-4: the code first, or a comma between
            "Press 4829 on your keypad to repeat the verification code",
            "کد تایید ارسال شد، نوبت شما در مطب دکتر نمونه 482913",
            "Verification code accepted, welcome to Example, your member card 482913 is active",
            // a veto word six words before the code
            "Your verification code for tracking your Example Shop order today is 482913",
        ).forEach { assertTrue(it, shown(it)); assertFalse(it, allows(it)) }
        val filler = " Thank you for choosing Example. We are glad to have you with us today.".repeat(30)
        val link = "https://example.com/login"
        assertShownOnly("Your verification code for signing up to the Example app is 482913.$filler $link", "482913")
    }

    /** Lead, R5-5: a request to hand the code to someone, and betting or lottery words, never allow. */
    @Test fun aHandOverRequestOrBettingNeverAllows() {
        listOf(
            "Your OTP for bonus credit at Example Bet is 482913",
            "Tu código de verificación para el sorteo de la tienda Ejemplo es 482913",
            "Ihr Bestätigungscode für das Gewinnspiel im Beispiel Markt lautet 482913",
            "حساب شما مسدود شد، کد تایید ارسالی را به کارشناس ما اعلام کنید 482913",
            "Reply with the verification code to confirm you are the owner 482913",
            // R6-4
            "Your verification code for unblocking your account at Example Bank is 482913 provide it to our officer",
            "کد تایید 482913 را به پشتیبان اعلام کنید",
            "کد تایید شما در سایت پیش بینی نمونه 482913",
            "Your verification code for the Example raffle entry is 482913",
            "Your OTP for the Example sweepstakes entry this month is 482913",
            "Seu código de verificação para apostas na Exemplo é 482913",
        ).forEach { assertShownOnly(it, "482913") }
        // "bet" inside a name is no betting word.
        assertShownAndAllows("Your Bet365 verification code is 482913", "482913")
    }

    /** Review 6, R6-2: a warning not to hand the code over is no request; the code allows. */
    @Test fun aWarningIsNoHandOverRequest() {
        listOf(
            "Your OTP is 482913. Never tell the agent your OTP",
            "Your OTP is 482913. Example will never ask you to share the code with our agent",
            "Your OTP is 482913. Do not share or give the code to the caller",
            "Your OTP is 482913. Do not reply with the code",
            "Your OTP is 482913. Do not share it with the courier or anyone else",
            "کد تایید: 482913 این کد را به کارشناس ما اعلام نکنید",
            "کد تایید: 482913 کارشناسان ما هرگز از شما نمی خواهند کد را به کارشناس اعلام کنید",
            "کد تایید شما 482913 است. از شما نمی خواهیم کد را به اپراتور بگویید",
        ).forEach { assertShownAndAllows(it, "482913") }
        // "asks" alone is no warning, and a comma ends the clause of one.
        listOf(
            "Your OTP is 482913. Example asks you to share the code with our agent",
            "Your OTP is 482913. As asked, give the OTP to our officer",
            "Don't worry, share the code with our agent 482913",
            "Your OTP is 482913. If not received, give the OTP to our agent",
            "هیچ نگرانی نیست، کد تایید را به کارشناس ما اعلام کنید 482913",
            "Don't worry; share the code with our agent 482913",
            // "if not," is no negation of the request after it
            "Your OTP is 482913. If not, share the code with our agent",
        ).forEach { assertShownOnly(it, "482913") }
        // A comma right after the negation, or around an aside, keeps the warning; so does a longer clause.
        listOf(
            "Your OTP is 482913. Never, ever share the code with the agent",
            "Your OTP is 482913. Do not, under any circumstances, share the code with our agent",
            "Your OTP is 482913. We never call or ask you to share the OTP with our staff",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /**
     * Lead, R6-3: a ride or delivery code may be shown to the driver or courier, unless the text is about a bank, a
     * payment, an account, a card or a login; an agent, a caller, an officer or support never needs the code.
     */
    @Test fun aRideOrDeliveryCodeMayGoToTheDriver() {
        assertShownAndAllows("Your Uber code is 4829. Share this code with your driver", "4829")
        assertShownAndAllows("کد تایید تحویل: 482913 این کد را به پیک بگویید", "482913")
        assertShownAndAllows("Share OTP 4829 with the rider to receive your order", "4829")
        assertShownAndAllows("Order 48291 arriving today, give the OTP to the courier", "48291")
        assertShownOnly("Your Example Bank OTP is 482913. Share it with the courier at the door", "482913")
        assertShownOnly("Your OTP is 482913. Share it with the agent who calls you", "482913")
    }

    /**
     * Review 6: R6-6 (login codes allow, door and car park codes do not), R6-5 (two phrases, a comma before the
     * code), R6-7 (notice side).
     */
    @Test fun wideReachLimitsOfReviewSix() {
        // Lead, R6-6 as corrected: a login code allows, a physical access code (door, car park) does not.
        assertShownOnly("The security code for the main entrance door this month is 4829", "4829")
        assertShownOnly("کد ورود پارکینگ ساختمان نمونه برای مهمانان این هفته 4829", "4829")
        assertShownOnly("کد ورود درب ساختمان برای پیک و مهمان این ماه 4829", "4829")
        assertShownAndAllows("کد ورود شما به سامانه نمونه در این دستگاه 482913", "482913")
        assertShownAndAllows("کد ورود به جلسه آنلاین مدرسه نمونه برای اولیا 482913", "482913")
        assertShownAndAllows("Your security code for the Example app on this device is 482913", "482913")
        assertShownAndAllows("Your security code is 482913", "482913")
        // "Your door" is where a parcel arrives, not a door code.
        val delivery = "Order number 77123: verification code for confirming the delivery at your door is 482913"
        assertShownAndAllows(delivery, "482913")
        assertShownOnly("Door code 4821 - do not share", "4821")
        assertShownOnly("Your door code is 4821", "4821")
        assertShownOnly("The security code for the door this month is 4829", "4829")
        listOf(
            "Your building access code is 4829",
            "Entrance PIN 4829",
            "Your parking access code is 4829",
            "Parking PIN: 4829",
            "Your gate PIN is 4829",
        ).forEach { assertShownOnly(it, "4829") }
        assertShownAndAllows("Your verification code for the delivery at your front door is 482913", "482913")
        // Harmless uses of the access words: a building society, a parking app, a door delivery, a gate number.
        listOf(
            "Your OTP for Nationwide Building Society login is 482913",
            "Your PayByPhone parking verification code is 482913",
            "OTP for door delivery is 482913",
            "Your OTP for gate number change is 482913",
            "Gate no. OTP 482913",
            "Gate number OTP 482913",
            "Parking fine OTP 482913",
            "Your OTP for parking fee payment is 482913",
            "Your OTP for the parking fine is 482913",
            "Your OTP for your parking ticket is 482913",
            "Your OTP for the parking permit renewal is 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        // "gate now" is no gate number, and a bare gate or parking word right before the code vetoes.
        assertShownOnly("Use the gate now with PIN 4829", "4829")
        assertShownOnly("Gate now OTP 482913", "482913")
        assertShownOnly("Gate OTP 482913", "482913")
        assertShownOnly("Parking OTP 482913", "482913")
        // Access words are phrases: an entrance exam or a parking payment is no door.
        assertShownAndAllows("Your OTP for the entrance exam registration is 482913", "482913")
        assertShownAndAllows("OTP for parking payment at Example is 482913", "482913")
        assertShownAndAllows("رمز پویا، کد تایید شما برای خرید از فروشگاه نمونه: 482913", "482913")
        assertShownAndAllows("کد تایید شما برای ورود به سامانه نمونه، 482913", "482913")
        assertShownAndAllows("Your OTP for the purchase on card no. 1234 at Example Store is 482913", "482913")
        listOf("Your verification code at gate 4829", "Your OTP for terminal 4829", "Seat 4829 verification code")
            .forEach { assertShownOnly(it, "4829") }
    }

    /**
     * The step word may stand right before its number or carry a full stop ("Rs.2500", "USD49.99", "ending1234"):
     * the separator of the in-sentence step may be empty.
     */
    @Test fun aStepWordGluedToItsNumberIsSteppedOver() {
        listOf(
            "OTP for txn of Rs.2500 at EXAMPLE is 482913",
            "OTP for purchase of USD49.99 at Example Store today is 482913",
            "Your OTP for the transaction on card ending1234 at Example is 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** Review 5, R5-2: flight, gate, terminal, seat and پذیرنده name a number only right before it. */
    @Test fun travelAndMerchantWordsNameOnlyTheNumberRightAfterThem() {
        assertShownOnly("Your passcode for flight 482913", "482913")
        assertShownOnly("کد پذیرنده 482913", "482913")
        assertShownAndAllows("Gate.io verification code: 482913", "482913")
        assertShownAndAllows("کد تایید شارژ اعتبار: 482913", "482913")
    }

    /** Owner, 21 Nov: the wide allow needs no link, no veto word between, and a one-time code phrase. */
    @Test fun shape1TheWideAllowHasItsLimits() {
        // A link anywhere in the text: shown only.
        assertShownOnly("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913\nexample.com/shop", "482913")
        assertShownOnly("Your verification code for signing up to Example is 482913. example.com/help", "482913")
        // A veto word between the phrase and the code: shown only.
        assertShownOnly("کد تایید شما برای پیگیری سفارش در فروشگاه نمونه: 482913", "482913")
        assertShownOnly("Your verification code for the tracking page of Example is 482913", "482913")
        // A bare strong word or a password word far from the code: never allows.
        assertFalse(allows("لطفا برای تایید حساب کاربری خود در سامانه نمونه این عدد را وارد کنید: 482913"))
        assertFalse(allows("Your password for the new account at Example is 482913"))
        // A sentence break between the phrase and the code: not the wide reach.
        assertFalse(allows("کد تایید ارسال شد. لطفا آن را در صفحه ورود سامانه نمونه وارد کنید 482913"))
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
        assertShownAndAllows("Your OTP from Shop24 is 482913", "482913")
        assertShownAndAllows("کد تایید اپلیکیشن نمونه۳۶۵ شما: ۴۸۲۹۱۳", "482913")
        assertShownAndAllows("Your verification code for the Examplecloudservices app is 482913", "482913")
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
        // A number right after an amount word is an amount, also next to a code phrase (review 3, R3-1).
        assertNoCode("کد تایید شما برای برداشت مبلغ: 482913")
        assertShownAndAllows("Your verification code for your Example account is 482913", "482913")
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
        assertNoCode("برای دریافت کلمه عبور با 5550001234 تماس بگیرید")
        assertNoCode("پسورد را به شماره 5550001234 ارسال کنید")
        // R3-3: a number to send to a short code is shown, but allows nobody.
        assertTrue(shown("برای دریافت پسورد عدد 4829 را به 30001234 ارسال کنید"))
        assertFalse(allows("برای دریافت پسورد عدد 4829 را به 30001234 ارسال کنید"))
        assertShownAndAllows("رمز عبور شما 5550001234 است", "5550001234")
    }

    /**
     * R3-1: the chain steps over a number that a reference or amount word names, or a masked one, so the code after
     * it is found, copied and allowed, never the reference number.
     */
    @Test fun theChainStepsOverAReferenceNumber() {
        listOf(
            "OTP for card ending 1234 is 482913",
            "Your OTP for A/c ending 1234 is 482913",
            "Your OTP for account ending 1234 is 482913. Do not share.",
            "Your OTP for card ending in 1234 is 482913",
            "رمز پویا کارت منتهی به 1234: 482913",
            "OTP for card *1234 is 482913",
            "OTP for a/c *1234: 482913",
            "رمز پویا برای کارت *1234: 482913",
            "Your OTP for card **1234 is 482913",
            "Your password reset code for account number 12345678 is 482913",
            "Your OTP for order number 12345678 is 482913",
            "OTP for transaction of INR 2500.00 is 482913",
            "Your OTP for transaction amount 2500 is 482913",
            "OTP for transaction on card ending 1234 is 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        assertShownAndAllows("Your Example verification code for phone ending 4829 is 771234", "771234")
        assertEquals(
            "Your OTP for transaction amount 2500 is ••••",
            com.glooboo.umessage.helpers.CodeMasker.mask("Your OTP for transaction amount 2500 is 482913"),
        )
        // A notice whose second number follows a masked card and "انجام شد" (done) allows nobody.
        assertFalse(allows("خرید با رمز پویا از کارت *1234 انجام شد 482913"))
        assertFalse(allows("Password changed for account ending 1234. Case 482913"))
    }

    /** R3-6: a reference word starts a word: "sending" and "spending" are no "ending". */
    @Test fun aReferenceWordMustStartAWord() {
        assertShownAndAllows("We are sending 482913 as your verification code", "482913")
        assertShownAndAllows("Sending 482913 as your OTP", "482913")
    }

    /** R3-3: "عدد" (the number) introduces a code near a keyword; far from one it names a number. */
    @Test fun theWordForNumberIntroducesACode() {
        listOf(
            "کد تایید شما عدد 482913 است",
            "کد تایید: عدد 482913",
            "رمز یکبار مصرف شما عدد 482913 می باشد",
            "لطفا عدد 482913 را به عنوان کد تایید وارد کنید",
        ).forEach { assertShownAndAllows(it, "482913") }
        assertShownAndAllows("کد فعالسازی: عدد 4829", "4829")
    }

    /**
     * R3-4: words of a notice (wi-fi, modem, card, account) take the allow from a password or PIN only, never from a
     * one-time code phrase; a card change or expiry still does.
     */
    @Test fun noticeWordsDoNotTakeTheAllowFromAOneTimeCode() {
        listOf(
            "Your WiFi OTP is 482913",
            "Your Wi-Fi login code is 482913",
            "رمز یکبار مصرف وای فای: 482913",
            "کد ورود به وای فای نمونه: 482913",
            "رمز یکبار مصرف ورود به پنل مودم: 482913",
            "رمز التحقق لحساب نمونه هو 482913",
            "رمز التحقق لتفعيل بطاقتك هو 482913",
            "رمز التحقق لمرة واحدة لبطاقتك هو 482913",
            "رمز التحقق رقم 482913",
        ).forEach { assertShownAndAllows(it, "482913") }
        listOf(
            "پسورد وای فای 48291375",
            "Wi-Fi password: 48291375",
            "الرقم السري لبطاقة الصراف 1234 تم تفعيله",
            "تم تغيير الرقم السري لبطاقتك المنتهية بـ 1234",
            "تم تحديث كلمة السر لحسابك رقم 482913",
        ).forEach { assertFalse(it, allows(it)) }
    }

    /** R3-5: a long password with a support line or "tell no one" after it is a code. */
    @Test fun aLongPasswordWithASupportLineIsACode() {
        listOf(
            "رمز شخصی شما 1234567890. تماس با پشتیبانی 02112345678",
            "Your password is 1234567890. Call us if you did not request it.",
            "Your temporary password is 1234567890 tell no one",
            "Your password is 1234567890 (telegram: @example)",
        ).forEach { assertShownAndAllows(it, "1234567890") }
    }

    /** R3-8: an origin-bound line vouches for a labelled value without a keyword. */
    @Test fun anOriginBoundLineVouchesForALabelledValue() {
        assertShownAndAllows("pass: 482913\n@example.com #482913", "482913")
    }

    /** Review 4, R4-1: a masked account or card number in a notice allows nobody, whatever word stands before it. */
    @Test fun aMaskedAccountInANoticeAllowsNobody() {
        listOf(
            "از حساب *1234 برداشت شد. رمز خود را به کسی ندهید",
            "رمز دوم کارت شما *1234 فعال شد",
            "رمز پویا کارت شماره *1234 فعال شد",
            "خرید با رمز پویا از کارت بانکی *1234",
            "OTP used on card no. *1234",
            "OTP used on card number *1234",
            "Password for A/C no. *1234 updated",
            "PIN set for Credit Card No *1234. Do not share your PIN",
            "Login to account *1234 with your password",
            "Your password for user *4829 was reset",
        ).forEach { assertFalse(it, allows(it)) }
    }

    /** Review 4, R4-3: a fee, a date, a time or a duration after a stepped-over number allows nobody. */
    @Test fun aFeeDateTimeOrDurationAllowsNobody() {
        listOf(
            "کد تایید به شماره 05550001234 ارسال شد. اعتبار 1200 ثانیه",
            "رمز یکبار مصرف به شماره 05550001234 ارسال گردید ساعت 1432",
            "رمز پویا مبلغ 2500000 ریال تاریخ 14050830 ساعت 1432",
            "OTP for USD 25 payment declined 482913",
            "خرید با رمز پویا مبلغ 1500000 کارمزد 5000",
            "OTP txn amount 2500 date 20261121",
            "Dear customer, never share OTP. A/c ending 1234 credited with INR 25000 on 21Nov. Ref 482913",
        ).forEach { assertFalse(it, allows(it)) }
        // The same words after the code do not take its allow.
        listOf(
            "رمز پویا 482913 کارمزد 5000 ریال",
            "رمز یکبار مصرف 482913 اعتبار 120 ثانیه",
            "OTP 482913 valid for 120 seconds",
            "کد تایید 482913 تاریخ 1405/08/30 ساعت 14:32",
        ).forEach { assertShownAndAllows(it, "482913") }
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
        // R3-2: one "#", star or bullet before four digits without a card word is no mask.
        listOf("Your OTP is #4829", "کد تایید: #4829", "OTP *4829", "PIN •4829", "Your verification code is: •4829")
            .forEach { assertShownAndAllows(it, "4829") }
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
     * The fastest of [RUNS] full checks of [body], in milliseconds, after one run to warm the classes up. The fastest
     * run is what the patterns cost; the slower ones hold the pauses of a busy machine (a parallel test suite).
     */
    private fun bestMillis(body: String): Long {
        check("Your OTP is 482913")
        check(body)
        return (1..RUNS).minOf {
            val start = System.nanoTime()
            check(body)
            (System.nanoTime() - start) / NANOS_PER_MILLI
        }
    }

    /**
     * N5-1, N5-2: texts that made the patterns backtrack for seconds (a mark fitted both a word and a separator). The
     * best of three runs must stay under [FAST_MILLIS]: ten times what the patterns take today, and still far below
     * the seconds of the old patterns, which hang here (hence the timeout).
     */
    private fun assertFast(body: String) {
        val millis = bestMillis(body)
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

    /**
     * Review 6, R6-1: a bracketed dot repeated started a link match at every label; one start only now. On the JVM
     * the link pattern overflows the stack on such a run (R3-10, caught), so only the device test (ICU) measures the
     * old cost of seconds; here the bound is one second.
     */
    @Test(timeout = TIMEOUT_MILLIS) fun aRepeatedBracketedDotIsFast() {
        val phrase = "Your verification code for signing up to the Example app is 482913 "
        for (length in listOf(2000, 8000, 16000)) {
            // A disguised link ("a[.]a[.]...[.]com"): the code is shown, but its sender is not allowed.
            val body = phrase + "a[.]".repeat((length - phrase.length) / 4) + "com"
            val millis = bestMillis(body)
            assertTrue("$length: $millis ms", millis < LINK_MILLIS)
            assertTrue("$length", shown(body))
            assertFalse("$length", allows(body))
        }
    }

    /** Review 5, R5-1: "rs *12" read in many ways made the chains run for seconds; every element is atomic now. */
    @Test(timeout = TIMEOUT_MILLIS) fun aRepeatedCurrencyAndMaskIsFast() {
        val unit = ("OTP" + " rs *12".repeat(400))
        assertFast(unit.take(100))
        assertFast(unit.take(160))
        assertFast(unit.take(2000))
        assertFast(("OTP" + " card number *1234".repeat(200)).take(2000))
        assertFast(("رمز پویا" + " حساب شماره *1234 کارت شماره *1234".repeat(100)).take(2000))
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
        const val FAST_MILLIS = 500L
        const val RUNS = 3
        const val LINK_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 30_000L
    }
}
