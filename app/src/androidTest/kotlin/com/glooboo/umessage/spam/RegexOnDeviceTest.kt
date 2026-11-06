package com.glooboo.umessage.spam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.helpers.OtpCodeExtractor
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

    /** Objects whose static initializer compiles a Regex or Pattern. */
    private val regexHolders = listOf(
        "com.glooboo.umessage.spam.engine.OtpDetector",
        "com.glooboo.umessage.spam.TextNormalizer",
        "com.glooboo.umessage.spam.RegexGuard",
        "com.glooboo.umessage.helpers.OtpCodeExtractor",
        "com.glooboo.umessage.helpers.SmsIntentParser",
        "com.glooboo.umessage.ui.spam.BlockedNumberFormat",
        "com.glooboo.umessage.ui.spam.RuleValidator",
        "com.glooboo.umessage.ui.spam.RuleTextCodec",
        "com.glooboo.umessage.helpers.CodeMasker",
        "com.glooboo.umessage.ui.components.NumberTextKt",
        "com.glooboo.umessage.ui.components.PhoneNumberGrouping",
        "com.glooboo.umessage.ui.components.PhoneNumberListDisplayKt",
        "com.glooboo.umessage.ui.conversations.ConversationTextKt",
        "com.glooboo.umessage.ui.log.ForwardLogText",
        "com.glooboo.umessage.ui.spam.BlockedHints",
        "com.glooboo.umessage.ui.spam.RestoredLogCodec",
        "com.glooboo.umessage.ui.spam.ForwardTargets",
        "com.glooboo.umessage.ui.thread.ThreadCodesKt",
        "com.glooboo.umessage.ui.thread.ThreadLinksKt",
    )

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
}
