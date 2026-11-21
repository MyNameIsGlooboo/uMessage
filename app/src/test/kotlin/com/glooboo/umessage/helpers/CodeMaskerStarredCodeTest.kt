package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Step N second review: a code written between stars is copied without them and still hidden on the lock screen,
 * stars kept around the mark; a masked card number is not a code, so nothing is hidden. All numbers are made up.
 */
class CodeMaskerStarredCodeTest {

    @Test fun aLabelledCodeBetweenStarsIsHidden() {
        assertEquals("OTP: *••••*", CodeMasker.mask("OTP: *482913*"))
        assertEquals("کد: *••••*", CodeMasker.mask("کد: *4829*"))
    }

    @Test fun aCodeBetweenStarsNextToAKeywordIsHidden() {
        assertEquals("Your verification code is *••••*", CodeMasker.mask("Your verification code is *482913*"))
        assertEquals("کد تایید شما: **••••**", CodeMasker.mask("کد تایید شما: **۴۸۲۹۱۳**"))
    }

    @Test fun aMaskedCardNumberIsLeftAsItIs() {
        listOf("Your PIN for card ****1234 was changed", "خرید با رمز پویا از کارت *1234 انجام شد")
            .forEach { assertEquals(it, CodeMasker.mask(it)) }
    }
}
