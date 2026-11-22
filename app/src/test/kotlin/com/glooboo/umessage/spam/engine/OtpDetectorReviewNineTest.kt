package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.helpers.OtpCodes
import com.glooboo.umessage.spam.KeywordMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 9 of the OTP detector (R9-1, the small items of R9-3), two leftovers of the earlier reviews (R2-10 no-break
 * spaces, R3-10 a long run of labels in the link pattern) and the J7 review. Every text, brand and number is made up.
 */
class OtpDetectorReviewNineTest {

    private fun shown(body: String) = OtpDetector.looksLikeOtp(body)

    private fun allows(body: String) = AllowingCode.isAllowingCode(body)

    private fun copied(body: String) = OtpCodes.tokens(body).map { it.value }

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

    /** R9-1: "teams" or "Teams" is no receiver; staff, support and a team still are, and agents in the plural. */
    @Test fun aTeamInThePluralIsNoReceiver() {
        listOf(
            "Your OTP is 482913. Share it with our teams",
            "Your OTP is 482913. Send it to Teams",
        ).forEach { assertShownAndAllows(it, "482913") }
        listOf(
            "Your OTP is 482913. Share it with our support team",
            "Your OTP is 482913. Share it with our support teams",
            "Your OTP is 482913. Share OTP with our staff",
            // J7-2: "staffs" is still staff.
            "Your OTP is 482913. Share it with our staffs",
            "Your OTP is 482913. Share it with our agents",
            "Your OTP is 482913. Give the code to our officers",
        ).forEach { assertShownOnly(it, "482913") }
    }

    /** R9-3: a plural code, three words before the Persian verb, "never mind" and "no one else" are requests. */
    @Test fun smallRequestsOfReviewNine() {
        listOf(
            "Your OTP is 482913. Share codes with our staff",
            "Your OTP is 482913. Share OTPs with our staff",
            "کد تایید 482913 را به کارشناس ما لطفا سریعا اعلام کنید",
            "Your OTP is 482913. Never mind the delay share the OTP with our agent",
            "No one else can help share the OTP with our agent. Your OTP is 482913",
        ).forEach { assertShownOnly(it, "482913") }
        listOf(
            "Your OTP is 482913. Never share codes with our staff",
            "کد تایید 482913 را هرگز به کارشناس ما لطفا سریعا اعلام نکنید",
            "Your OTP is 482913. Never share the OTP with our agent",
            "Your OTP is 482913. No one will ask you to share the OTP with our agent",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** J7-1: two spaces, a tab or one line break between the words of a request still make a request. */
    @Test fun aRequestAcrossSpacesOrALineBreakIsARequest() {
        listOf(
            "Your OTP is 482913. Share it with  our agent",
            "Your OTP is 482913. Share it with\nour agent",
            "Your OTP is 482913. Share it\nwith our agent",
            "Your OTP is 482913. Share the OTP with our\nagent",
            "Your OTP is 482913. Share it with\tour agent",
            "Your OTP is 482913. Share the  code  with our  support team",
            "کد تایید 482913 را به کارشناس  ما  اعلام کنید",
        ).forEach { assertShownOnly(it, "482913") }
        listOf(
            "Your OTP is 482913. Never share it with  our agent",
            "Your OTP is 482913. Do not share the OTP with\nour agent",
            "کد تایید 482913 را هرگز به کارشناس  ما  اعلام نکنید",
        ).forEach { assertShownAndAllows(it, "482913") }
    }

    /** R9-3, J7-7: a hyphen after anything but a label character does not hide a link. */
    @Test fun aHyphenAfterABracketStartsALink() {
        listOf("(-example.com)", "[-example.com]", ")-example.com", "\"-example.com\"", "'-example.com'")
            .plus("«-example.com»")
            .forEach { assertTrue(it, OtpDetector.containsUrl("Your code is 482913 $it")) }
        assertFalse(OtpDetector.containsUrl("Your code is 482913 (--example.com)"))
        assertFalse(allows("Your code is 482913 (-example.com)"))
        assertShownAndAllows("Your code is 482913 (-valid for 10 min.)", "482913")
    }

    /** R2-10: a no-break, narrow or ideographic space reads as a plain space, on the JVM as on a phone. */
    @Test fun noBreakSpacesReadAsPlainSpaces() {
        listOf(
            "کد\u00A0تایید 482913" to "کد تایید 482913",
            "OTP 482913\u00A0%" to "OTP 482913 %",
            "OTP 4829\u202Fتومان" to "OTP 4829 تومان",
            "log\u00A0in 482913" to "log in 482913",
            "tracking number\u00A0is 482913 OTP" to "tracking number is 482913 OTP",
            "验证码\u3000482913" to "验证码 482913",
            "Your OTP is 482913\u2007ریال" to "Your OTP is 482913 ریال",
            // J7-4: next-line and the line and paragraph separators read as a line break.
            "Your OTP is 482913\u2028%" to "Your OTP is 482913\n%",
            "log\u2029in 482913" to "log\nin 482913",
            "Your OTP is\u0085482913" to "Your OTP is\n482913",
        ).forEach { (spaced, plain) ->
            assertEquals(spaced, shown(plain), shown(spaced))
            assertEquals(spaced, allows(plain), allows(spaced))
            assertEquals(spaced, copied(plain), copied(spaced))
        }
        assertTrue(allows("کد\u00A0تایید 482913"))
        assertFalse(shown("OTP 482913\u00A0%"))
        // J7-8: the mask finds the code behind a no-break space and keeps that space; a keyword rule reads it too.
        assertEquals("کد\u00A0تایید ••••••", CodeMasker.mask("کد\u00A0تایید 482913", mark = "••••••"))
        assertTrue(KeywordMatch.matches("Free\u00A0gift inside", "\"free gift\"", false))
        assertTrue(KeywordMatch.matches("Free\u00A0gift inside", "free gift", false))
    }

    /** R3-10: a long run of labels neither overflows the stack nor changes which domains are links. */
    @Test fun aLongRunOfLabelsStaysLinear() {
        for (unit in listOf("a.", "a[.]", "a-1.", "ab.")) {
            val run = "OTP " + unit.repeat(5_000)
            assertFalse(unit, OtpDetector.containsUrl(run))
            assertTrue(unit, OtpDetector.containsUrl(run + "com"))
            assertTrue(unit, OtpDetector.containsUrl(run + "zz/path"))
        }
        listOf(
            "Visit a.b.example.com.",
            "Visit a.b.com.c",
            "Visit x.example.zz/login",
            "Visit a.b.secure-bank.help",
            "Visit a1.b.help",
        ).forEach { assertTrue(it, OtpDetector.containsUrl(it)) }
        listOf("Visit a.b.c", "Visit example.comx", "Version 2.1.3", "Visit a.b.help-x").forEach {
            assertFalse(it, OtpDetector.containsUrl(it))
        }
    }
}
