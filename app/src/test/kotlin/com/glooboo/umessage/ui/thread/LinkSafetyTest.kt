package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I07: the offline link checks. Every address is invented or a well-known example. */
class LinkSafetyTest {

    private fun flags(uri: String) = LinkSafety.check(uri).flags

    @Test
    fun ordinaryLinksHaveNoFlag() {
        listOf(
            "https://example.com/path?x=1",
            "https://www.example.org",
            "https://pay.example.co.uk/invoice",
            "https://example.com./path",
            "https://EXAMPLE.COM/Path",
            "https://سایت.ir/x",
            "https://news.example.test:8443/a#b",
        ).forEach { assertThat(flags(it)).isEmpty() }
    }

    @Test
    fun shortenersAreFlagged() {
        assertThat(flags("https://bit.ly/abc")).containsExactly(LinkFlag.SHORTENER)
        assertThat(flags("https://www.bit.ly/abc")).containsExactly(LinkFlag.SHORTENER)
        assertThat(flags("https://t.co/xyz")).containsExactly(LinkFlag.SHORTENER)
        assertThat(flags("www.bit.ly/x")).containsExactly(LinkFlag.SHORTENER)
        assertThat(flags("http://tinyurl.com/x")).containsExactly(LinkFlag.SHORTENER, LinkFlag.HTTP_ONLY).inOrder()
    }

    @Test
    fun aSiteThatOnlyEndsLikeAShortenerIsNotOne() {
        assertThat(flags("https://orbit.ly.example.test/x")).isEmpty()
        assertThat(flags("https://notbit.ly/x")).isEmpty()
    }

    @Test
    fun punycodeIsFlagged() {
        val result = flags("https://xn--pple-43d.com/login")
        assertThat(result).contains(LinkFlag.PUNYCODE)
        assertThat(result).contains(LinkFlag.MIXED_SCRIPT)
    }

    @Test
    fun mixedAlphabetsInOneNameAreFlagged() {
        assertThat(flags("https://\u0430pple.com")).contains(LinkFlag.MIXED_SCRIPT)
        assertThat(flags("https://bank-\u0645\u0644\u062A.example.test")).contains(LinkFlag.MIXED_SCRIPT)
    }

    @Test
    fun separateNamesInOtherAlphabetsAreNotMixed() {
        assertThat(flags("https://\u0633\u0627\u06CC\u062A.example.test")).isEmpty()
    }

    @Test
    fun rawIpAddressesAreFlagged() {
        assertThat(flags("http://192.168.1.1/login")).containsExactly(LinkFlag.RAW_IP, LinkFlag.HTTP_ONLY).inOrder()
        assertThat(flags("https://192.168.1.1:8080")).containsExactly(LinkFlag.RAW_IP)
        assertThat(flags("https://192.168.1.1./x")).containsExactly(LinkFlag.RAW_IP)
        assertThat(flags("https://3232235777/x")).containsExactly(LinkFlag.RAW_IP)
        assertThat(flags("https://0x7f.0x0.0x0.0x1/")).containsExactly(LinkFlag.RAW_IP)
        assertThat(flags("https://[2001:db8::1]/x")).containsExactly(LinkFlag.RAW_IP)
    }

    @Test
    fun shortNumbersAndVersionsAreNotAddresses() {
        assertThat(flags("https://1234567/x")).isEmpty()
        assertThat(flags("https://1.2.3/x")).isEmpty()
    }

    @Test
    fun plainHttpIsFlaggedButIsNotRisky() {
        assertThat(flags("http://example.com")).containsExactly(LinkFlag.HTTP_ONLY)
        assertThat(flags("HTTP://EXAMPLE.COM/X")).containsExactly(LinkFlag.HTTP_ONLY)
        assertThat(LinkSafety.check("http://example.com").isRisky).isFalse()
        assertThat(LinkSafety.check("https://bit.ly/x").isRisky).isTrue()
    }

    @Test
    fun swappedAndLookAlikeLettersAreFlagged() {
        val capitalI = LinkSafety.check("https://melIat.ir")
        assertThat(capitalI.flags).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(capitalI.lookalikeOf).isEqualTo("Bank Mellat")
        assertThat(LinkSafety.check("https://paypa1.com/login").lookalikeOf).isEqualTo("PayPal")
        assertThat(LinkSafety.check("https://rnellat.ir").lookalikeOf).isEqualTo("Bank Mellat")
        assertThat(LinkSafety.check("https://app1e.com").lookalikeOf).isEqualTo("Apple")
        assertThat(LinkSafety.check("https://paypla.com").lookalikeOf).isEqualTo("PayPal")
        assertThat(LinkSafety.check("https://goggle.com").lookalikeOf).isEqualTo("Google")
    }

    @Test
    fun brandNamesInsideAnotherAddressAreFlagged() {
        assertThat(flags("https://secure-paypal.com")).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(flags("https://paypal-login.net/x")).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(flags("https://mellat-bank.ir")).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(flags("https://login.mellat-pay.example.test")).isEmpty()
    }

    @Test
    fun aBanksNameOnAnotherAddressIsFlaggedButARealOneIsNot() {
        assertThat(flags("https://bankmellat.ir/login")).isEmpty()
        assertThat(flags("https://www.bankmellat.ir")).isEmpty()
        assertThat(flags("https://bankmellat.com")).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(flags("https://bmi.ir")).isEmpty()
    }

    @Test
    fun aBrandOwningItsNameEverywhereIsNotFlaggedForItsOwnName() {
        assertThat(flags("https://paypal.com")).isEmpty()
        assertThat(flags("https://www.paypal.com/x")).isEmpty()
        assertThat(flags("https://paypal.de")).isEmpty()
        assertThat(flags("https://apple.com")).isEmpty()
        assertThat(flags("https://maple.com")).isEmpty()
        assertThat(flags("https://exampel.com")).isEmpty()
    }

    @Test
    fun persianBankNamesOnAnAddressAreFlagged() {
        val report = LinkSafety.check("https://\u0628\u0627\u0646\u06A9-\u0645\u0644\u062A.ir/login")
        assertThat(report.flags).containsExactly(LinkFlag.LOOKALIKE)
        assertThat(report.lookalikeOf).isEqualTo("Bank Mellat")
        val withJoiner = "https://بانک‌ملت.ir"
        assertThat(flags(withJoiner)).containsExactly(LinkFlag.LOOKALIKE)
    }

    @Test
    fun aNameBeforeTheAtSignIsFlagged() {
        assertThat(flags("https://paypal.com@evil.example.test/login")).containsExactly(LinkFlag.USER_INFO)
        assertThat(flags("https://user:pw@example.com/x")).containsExactly(LinkFlag.USER_INFO)
    }

    @Test
    fun mailAndEmptyLinksHaveNoFlag() {
        assertThat(flags("mailto:sam@example.com")).isEmpty()
        assertThat(flags("")).isEmpty()
        assertThat(flags("   ")).isEmpty()
        assertThat(LinkSafety.check("https://example.com").isClean).isTrue()
    }

    @Test
    fun flagsComeInAFixedOrder() {
        val result = flags("http://paypa1.com@bit.ly/x")
        assertThat(result).containsExactly(
            LinkFlag.SHORTENER, LinkFlag.HTTP_ONLY, LinkFlag.USER_INFO,
        ).inOrder()
    }

    @Test
    fun distanceCountsASwapAsOneChange() {
        assertThat(EditDistance.between("paypla", "paypal")).isEqualTo(1)
        assertThat(EditDistance.between("mellat", "mellat")).isEqualTo(0)
        assertThat(EditDistance.between("mellat", "melat")).isEqualTo(1)
        assertThat(EditDistance.between("abc", "xyz")).isEqualTo(3)
    }
}
