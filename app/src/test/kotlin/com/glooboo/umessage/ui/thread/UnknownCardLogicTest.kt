package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I04: when the card shows and when it cautions. All texts are invented. */
class UnknownCardLogicTest {

    private fun show(
        unknown: Boolean = true,
        received: Boolean = true,
        bin: Boolean = false,
        senderName: Boolean = false,
        rule: Boolean = false,
        dismissed: Boolean = false,
    ) = UnknownCardLogic.shouldShow(unknown, received, bin, senderName, rule, dismissed)

    @Test
    fun showsForAStrangerWhoWroteToTheUser() {
        assertThat(show()).isTrue()
    }

    @Test
    fun hiddenForContactsGroupsAndFamiliarSenders() {
        assertThat(show(unknown = false)).isFalse()
    }

    @Test
    fun hiddenWhenNothingWasReceivedOrInTheBin() {
        assertThat(show(received = false)).isFalse()
        assertThat(show(bin = true)).isFalse()
    }

    @Test
    fun hiddenForSenderNamesRulesAndDismissedCards() {
        assertThat(show(senderName = true)).isFalse()
        assertThat(show(rule = true)).isFalse()
        assertThat(show(dismissed = true)).isFalse()
    }

    @Test
    fun linkGetsACaution() {
        assertThat(UnknownCardLogic.needsCaution("Your parcel is held, see https://example.test/track")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("Pay at www.example.test now")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("see bit.ly/abc123")).isTrue()
    }

    @Test
    fun amountGetsACaution() {
        assertThat(UnknownCardLogic.needsCaution("Please transfer \$250 today")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("You owe 40 USD")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("Amount: 1,200 due")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("لطفا ۵۰۰۰۰ تومان واریز کنید")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("مبلغ ۱۲۰۰۰۰ ریال")).isTrue()
    }

    @Test
    fun codeRequestGetsACaution() {
        assertThat(UnknownCardLogic.needsCaution("Hi, please send me the code you just got")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("Can you share the OTP with me?")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("کد را برای من بفرست")).isTrue()
        assertThat(UnknownCardLogic.needsCaution("لطفا رمز یکبار مصرف را بگویید")).isTrue()
    }

    @Test
    fun ordinaryTextHasNoCaution() {
        assertThat(UnknownCardLogic.needsCaution("Hi, is this Sam? Dinner at 8 tomorrow.")).isFalse()
        assertThat(UnknownCardLogic.needsCaution("سلام، فردا ساعت ۸ می‌بینمت")).isFalse()
        assertThat(UnknownCardLogic.needsCaution("I have 3 cats and a postcode of 1234")).isFalse()
        assertThat(UnknownCardLogic.needsCaution("")).isFalse()
        assertThat(UnknownCardLogic.needsCaution(null)).isFalse()
    }
}
