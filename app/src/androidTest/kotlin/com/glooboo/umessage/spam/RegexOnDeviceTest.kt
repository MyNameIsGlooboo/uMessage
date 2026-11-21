package com.glooboo.umessage.spam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.engine.AllowingCode
import com.glooboo.umessage.spam.engine.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's regex engine (ICU) rejects some patterns desktop Java accepts, so JVM unit tests cannot catch a pattern
 * that breaks the app on a phone. This runs on a device: it initializes every object that compiles a regex when it
 * loads, and checks the spam patterns behave as the JVM tests expect.
 */
@RunWith(AndroidJUnit4::class)
class RegexOnDeviceTest {

    /** Classes that compile a Regex or Pattern; `RegexHoldersGuardTest` (JVM) fails when one is missing here. */
    private val regexHolders = listOf(
        "com.glooboo.umessage.databases.DatabaseBackups",
        "com.glooboo.umessage.export.ConversationFileFormat",
        "com.glooboo.umessage.export.ExportFileName",
        "com.glooboo.umessage.extensions.DialIntentKt",
        "com.glooboo.umessage.helpers.CodeMasker",
        "com.glooboo.umessage.helpers.LinkExtractor",
        "com.glooboo.umessage.helpers.MessageCategories",
        "com.glooboo.umessage.helpers.NotificationLinksKt",
        "com.glooboo.umessage.helpers.OtpCodeExtractor",
        "com.glooboo.umessage.helpers.OtpCodes",
        "com.glooboo.umessage.helpers.SavedTexts",
        "com.glooboo.umessage.helpers.SmsIntentParser",
        "com.glooboo.umessage.helpers.StarredMessages",
        "com.glooboo.umessage.helpers.smartreply.SmartReplyNormalizer",
        "com.glooboo.umessage.helpers.smartreply.SmartReplyPolicy",
        "com.glooboo.umessage.helpers.smartreply.SmartReplyText",
        "com.glooboo.umessage.helpers.smartreply.engine.EngineReading",
        "com.glooboo.umessage.spam.DecisionRuleText",
        "com.glooboo.umessage.spam.KeywordMatch",
        "com.glooboo.umessage.spam.RegexGuard",
        "com.glooboo.umessage.spam.TextNormalizer",
        "com.glooboo.umessage.spam.engine.AllowingCode",
        "com.glooboo.umessage.spam.engine.DomainMatch",
        "com.glooboo.umessage.spam.engine.OtpDetector",
        "com.glooboo.umessage.spam.engine.OtpPasswords",
        "com.glooboo.umessage.spam.mms.BlockedMmsStore",
        "com.glooboo.umessage.ui.components.NumberTextKt",
        "com.glooboo.umessage.ui.components.PhoneNumberDisplayKt",
        "com.glooboo.umessage.ui.components.PhoneNumberListDisplayKt",
        "com.glooboo.umessage.ui.labels.LabelRules",
        "com.glooboo.umessage.ui.log.ForwardLogText",
        "com.glooboo.umessage.ui.newconversation.GroupRecipientsKt",
        "com.glooboo.umessage.ui.spam.BlockedHints",
        "com.glooboo.umessage.ui.spam.BlockedNumberFormat",
        "com.glooboo.umessage.ui.spam.RestoredLogCodec",
        "com.glooboo.umessage.ui.spam.ForwardTargets",
        "com.glooboo.umessage.ui.spam.LegacyMigration",
        "com.glooboo.umessage.ui.spam.RuleLogicKt",
        "com.glooboo.umessage.ui.spam.RuleValidator",
        "com.glooboo.umessage.ui.spam.RuleSearch",
        "com.glooboo.umessage.ui.spam.RuleTextCodec",
        "com.glooboo.umessage.ui.thread.BankAlertParser",
        "com.glooboo.umessage.ui.thread.LinkSafety",
        "com.glooboo.umessage.ui.thread.PhoneMatchKt",
        "com.glooboo.umessage.ui.thread.TapbacksKt",
        "com.glooboo.umessage.ui.thread.ThreadLinksKt",
        "com.glooboo.umessage.ui.thread.UnknownCardLogic",
    )

    /** The fastest of three runs of [block] in milliseconds: a busy device slows some runs, never the patterns. */
    private fun bestMillis(block: () -> Unit): Long = (1..3).minOf {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000
    }

    @Test fun everyRegexHolderInitializes() {
        val loader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        for (name in regexHolders) {
            try {
                Class.forName(name, true, loader)
            } catch (t: Throwable) {
                throw AssertionError("$name failed to initialize on Android: ${t.cause ?: t}", t)
            }
        }
    }

    @Test fun otpDetectorBehavesAsOnTheJvm() {
        assertTrue(OtpDetector.looksLikeOtp("Your OTP is 482915"))
        assertTrue(OtpDetector.looksLikeOtp("رمز پویا: ۱۲۳۴۵۶"))
        assertTrue(OtpDetector.looksLikeOtp("كد تاييد شما: ۴۸۲۱"))
        assertTrue(OtpDetector.looksLikeOtp("کد\u200Cتایید: 1234"))
        assertFalse(OtpDetector.looksLikeOtp("Use promo code 2025 for 50% off today!"))
        assertFalse(OtpDetector.looksLikeOtp("کد تخفیف ۵۰۲۰ برای خرید اول شما. لغو۱۱"))
        assertFalse(OtpDetector.looksLikeOtp("Code price 4500 تومان"))
        assertFalse(OtpDetector.looksLikeOtp("کدام 1234"))

        assertTrue(OtpDetector.containsUrl("Your code 482913. Verify at mybank-secure.com"))
        assertTrue(OtpDetector.containsUrl("Hit example[.]com now"))
        assertTrue(OtpDetector.containsUrl("Hit example{.}com now"))
        assertTrue(OtpDetector.containsUrl("hxxps://evil.example"))
        assertTrue(OtpDetector.containsUrl("panel at 185.12.33.4/login"))
        assertFalse(OtpDetector.containsUrl("Questions? support@mybank.com"))
        assertFalse(OtpDetector.containsUrl("Price 1.50/month"))

        assertTrue(OtpDetector.isOriginBound("Your code is 123456\n\n@example.com #123456"))
        assertFalse(OtpDetector.containsUrl("Your code is 123456\n\n@www.example.com #123456"))
    }

    /**
     * Step N: the possessive runs, the bounded look-behinds (masks, reference words, dates) and the script ranges
     * behave on ICU as on the JVM, and a run of marks stays fast.
     */
    @Test fun stepNPatternsBehaveAsOnTheJvm() {
        // Wide reach from a code phrase, eight words of one sentence.
        assertTrue(OtpDetector.looksLikeOtp("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913"))
        // A code phrase with an advertising footer; an ad word in the code's sentence; a prize word anywhere.
        assertTrue(OtpDetector.looksLikeOtp("Your verification code is 482913. Get 20% off your next ride!"))
        assertFalse(AllowingCode.isAllowingCode("Your verification code is 482913. Get 20% off your next ride!"))
        assertFalse(OtpDetector.looksLikeOtp("Your OTP is 482913, enjoy 20% off"))
        assertFalse(OtpDetector.looksLikeOtp("Congratulations winner! Your OTP is 482913. Claim your prize"))
        // Masks and stars.
        assertFalse(OtpDetector.looksLikeOtp("Your PIN for card **** 1234 is now active"))
        assertFalse(OtpDetector.looksLikeOtp("Your PIN for card ••••1234 is now active"))
        assertFalse(OtpDetector.looksLikeOtp("خرید با رمز پویا از کارت *1234 انجام شد"))
        assertTrue(AllowingCode.isAllowingCode("OTP: *482913*"))
        assertEquals("482913", OtpCodeExtractor.extract("Your verification code is *482913*"))
        // Reference words and dates.
        assertTrue(AllowingCode.isAllowingCode("OTP for your account is 482913"))
        assertFalse(OtpDetector.looksLikeOtp("پرداخت قبض شما تایید شد، شماره پیگیری 482913"))
        assertFalse(OtpDetector.looksLikeOtp("Login at 2026-11-02 12:30"))
        // Separators of eight, Thai clauses, an emoji with a variation selector.
        assertTrue(AllowingCode.isAllowingCode("Verification code:\r\n\r\n482913"))
        assertTrue(AllowingCode.isAllowingCode("OTP สำหรับเข้าสู่ระบบของคุณคือ 482913"))
        assertTrue(AllowingCode.isAllowingCode("کد تایید ✅️482913"))
        // Combining marks are never separators: this would run for seconds if they were.
        val marks = "OTP " + "\u0301".repeat(200) + " 482913"
        assertFalse(OtpDetector.looksLikeOtp(marks))
        assertTrue("marks", bestMillis { OtpDetector.looksLikeOtp(marks) } < 1_000)
        // Review 5, R5-1: a currency word and a masked number repeated; every chain element is atomic.
        val unit = "OTP" + " rs *12".repeat(400)
        for (length in listOf(100, 160, 2000)) {
            val body = unit.take(length)
            val millis = bestMillis { OtpDetector.looksLikeOtp(body); AllowingCode.isAllowingCode(body) }
            assertTrue("rs *12 x $length: $millis ms", millis < 1_000)
        }
        // Review 6, R6-1: a bracketed dot repeated; the link pattern reads the whole body on ICU.
        val phrase = "Your verification code for signing up to the Example app is 482913 "
        for (length in listOf(2000, 8000, 16000)) {
            val body = phrase + "a[.]".repeat((length - phrase.length) / 4) + "com"
            val millis = bestMillis { AllowingCode.isAllowingCode(body) }
            assertTrue("a[.] x $length: $millis ms", millis < 2_000)
            assertFalse("a[.] x $length", AllowingCode.isAllowingCode(body))
        }
        // The wide allow (owner, 21 Nov) and the hand-over veto (R5-5, R6-2, R6-3).
        assertTrue(AllowingCode.isAllowingCode("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913"))
        assertFalse(AllowingCode.isAllowingCode("کد تایید ارسال شد، نوبت شما در مطب دکتر نمونه 482913"))
        assertTrue(AllowingCode.isAllowingCode("Order 48291 arriving today, give the OTP to the courier"))
        assertTrue(AllowingCode.isAllowingCode("Your OTP is 482913. Never tell the agent your OTP"))
        assertFalse(AllowingCode.isAllowingCode("Your Example Bank OTP is 482913. Share it with the courier"))
    }

    /** The strict reading that allows a sender (check 10) builds its own patterns on top of the detector's. */
    @Test fun allowingCodeBehavesAsOnTheJvm() {
        assertTrue(AllowingCode.isAllowingCode("Your verification code is 482915"))
        assertTrue(AllowingCode.isAllowingCode("کد تایید شما: ۴۸۲۹۱۵"))
        assertTrue(AllowingCode.isAllowingCode("Use 482915 in order to sign in"))
        assertFalse(AllowingCode.isAllowingCode("Sign in today and use code 4455"))
        assertFalse(AllowingCode.isAllowingCode("کد پیگیری سفارش شما 55667788"))
        assertFalse(AllowingCode.isAllowingCode("كود الخصم 4455 على طلبك القادم"))
    }

    @Test fun otpCodeExtractorBehavesAsOnTheJvm() {
        assertEquals("482915", OtpCodeExtractor.extract("Your OTP is 482915"))
        assertEquals("123456", OtpCodeExtractor.extract("رمز پویا: ۱۲۳۴۵۶"))
        assertEquals(
            "52814736",
            OtpCodeExtractor.extract("بانک\nرمز پویا: 52814736\nمبلغ: 1,250,000 ریال\nکارت: 5022-29**-****-4417\nزمان: 14:32"),
        )
        assertNull(OtpCodeExtractor.extract("Your parcel 48291 is on its way"))
    }

    @Test fun textNormalizerBehavesAsOnTheJvm() {
        assertEquals("علی 123", TextNormalizer.fold("علي ۱۲۳"))
        assertTrue(TextNormalizer.containsObfuscated("F R E E gift!", "free"))
        assertTrue(TextNormalizer.containsObfuscated("fr33 prize inside", "free"))
        assertTrue(TextNormalizer.containsObfuscated("CA\$H now", "cash"))
        assertFalse(TextNormalizer.containsObfuscated("of reeds", "free"))
        assertTrue(TextNormalizer.containsFolded("БЕСПЛАТНО приз!", "бесплатно"))
    }

    @Test fun regexGuardWorksWithAndroidFlags() {
        assertNotNull(RegexGuard.compile("бесплатно"))
        assertTrue(RegexGuard.find(RegexGuard.compile("бесплатно")!!, "БЕСПЛАТНО приз!"))
        assertNotNull(RegexGuard.compile("^\\s*(win|prize)\\b"))
        assertNotNull(RegexGuard.compile("[0-9]{4,8}"))
        assertNotNull(RegexGuard.compile("وام\\s*فوری"))
        assertEquals(RegexProblem.NESTED_REPEAT, RegexGuard.validate("(a+)+$"))
        assertEquals(RegexProblem.SYNTAX, RegexGuard.validate("([unclosed"))
        assertEquals(RegexProblem.MATCHES_EMPTY, RegexGuard.validate("a*"))
        assertNull(RegexGuard.validate("lottery"))
    }

    /** Android's regex engine matches natively, so only a real wall-clock wait can stop a slow pattern. */
    @Test fun slowPatternTimesOutOnTheDevice() {
        val pattern = RegexGuard.compile(".*.*.*.*x")
        assertNotNull(pattern)
        val start = System.nanoTime()
        val outcome = RegexGuard.search(pattern!!, "a".repeat(1_000))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(RegexGuard.Outcome.TIMED_OUT, outcome)
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        val lottery = RegexGuard.compile("lottery")!!
        assertEquals(RegexGuard.Outcome.MATCH, RegexGuard.search(lottery, "You won the LOTTERY"))
    }
}
