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

    /** Review 3, R3-2: the origin-bound line keeps its "#" code, hidden on the lock screen and in a forward. */
    @Test fun anOriginBoundCodeIsHiddenOnTheLockScreenAndInAForward() {
        val body = "Your code is 4829\n\n@example.com #4829"
        assertEquals("Your code is ••••\n\n@example.com #••••", CodeMasker.mask(body))
        assertEquals("Your code is ****\n\n@example.com #****", CodeMasker.maskForForward(body))
    }

    /** Review 3, R3-7: a labelled value written in keycap digits ("4️⃣8️⃣…") is hidden as a whole. */
    @Test fun aValueInKeycapDigitsIsHidden() {
        val keycaps = "Your code: 4\uFE0F\u20E38\uFE0F\u20E32\uFE0F\u20E39\uFE0F\u20E31\uFE0F\u20E33\uFE0F\u20E3"
        assertEquals("Your code: ••••", CodeMasker.mask(keycaps))
    }

    @Test fun aMaskedCardNumberIsLeftAsItIs() {
        listOf("Your PIN for card ****1234 was changed", "خرید با رمز پویا از کارت *1234 انجام شد")
            .forEach { assertEquals(it, CodeMasker.mask(it)) }
    }
}
