package com.glooboo.umessage.ui.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BR-01 and BR-18: the link and "may be important" hints on blocked rows. */
class BlockedHintsTest {

    @Test
    fun detectsLinks() {
        assertThat(BlockedHints.hasLink("Win now at http://x.example/a")).isTrue()
        assertThat(BlockedHints.hasLink("visit www.prize.top today")).isTrue()
        assertThat(BlockedHints.hasLink("claim at prize.xyz")).isTrue()
        assertThat(BlockedHints.hasLink("Your code is 482913")).isFalse()
    }

    @Test
    fun codeWithoutLinkIsImportant() {
        assertThat(BlockedHints.looksImportant("Your verification code is 482913")).isTrue()
        assertThat(BlockedHints.looksImportant("OTP 1234 for login")).isTrue()
    }

    @Test
    fun persianCodeAndDigitsAreRecognised() {
        assertThat(BlockedHints.looksImportant("سلام، کد تایید شما ۴۸۲۹ است")).isTrue()
        assertThat(BlockedHints.asciiDigits("۴۸۲۹ ٣٤٥٦ x")).isEqualTo("4829 3456 x")
    }

    @Test
    fun bankNoticeWithAmountIsImportant() {
        assertThat(BlockedHints.looksImportant("Debit of 120000 from your account")).isTrue()
    }

    @Test
    fun codeWithLinkIsImportantButBankNoticeWithLinkIsNot() {
        assertThat(BlockedHints.looksImportant("Your code 482913 http://evil.example")).isTrue()
        assertThat(BlockedHints.looksImportant("Debit of 120000 from your account http://evil.example")).isFalse()
    }

    @Test
    fun plainPromoIsNotImportant() {
        assertThat(BlockedHints.looksImportant("Big sale this weekend, 50% off")).isFalse()
        assertThat(BlockedHints.looksImportant("")).isFalse()
    }
}
