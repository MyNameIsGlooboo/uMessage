package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeMaskerTest {

    @Test fun masksPlainCode() =
        assertEquals("Your code is ••••. Do not share.", CodeMasker.mask("Your code is 482913. Do not share."))

    @Test fun keepsLastTwoDigitsWhenAsked() =
        assertEquals("Code: ****13", CodeMasker.mask("Code: 482913", keepLastTwo = true, mark = CodeMasker.ASTERISKS))

    @Test fun spacedCodeIsOneCode() =
        assertEquals("Login code: ****", CodeMasker.mask("Login code: 482 913", mark = CodeMasker.ASTERISKS))

    @Test fun masksEveryRepeatOfTheCode() =
        assertEquals("code **** (****)", CodeMasker.mask("code 482913 (482913)", mark = "****"))

    @Test fun persianDigitsAndRestOfTextStay() =
        assertEquals("رمز پویا: ****\nمبلغ: ۵۰۰", CodeMasker.mask("رمز پویا: ۵۲۸۱۴۷۳۶\nمبلغ: ۵۰۰", mark = "****"))

    @Test fun zeroWidthMarksInsideTheCodeAreCovered() =
        assertEquals("code ****!", CodeMasker.mask("code 48‌2913!", mark = "****"))

    @Test fun otherNumbersAreLeftAlone() =
        assertEquals(
            "Order 2026 shipped. Use code **** now.",
            CodeMasker.mask("Order 2026 shipped. Use code 739104 now.", mark = "****"),
        )

    @Test fun messageWithoutCodeIsUnchanged() {
        val text = "See you at 14:32, bring 5000 toman"
        assertEquals(text, CodeMasker.mask(text))
    }

    @Test fun forwardMaskFailsClosedWhenTheCodeIsNotLocated() =
        assertEquals("Your verification code is ****G", CodeMasker.maskForForward("Your verification code is 482913G"))

    @Test fun forwardMaskUsesThePreciseMaskWhenItCan() =
        assertEquals(
            "Order 2026 shipped. Use code **** now.",
            CodeMasker.maskForForward("Order 2026 shipped. Use code 739104 now."),
        )

    @Test fun digitRunsOfFourToEightAreHiddenInAnyScriptOthersStay() =
        assertEquals(
            "**** and **** but 123 and 123456789",
            CodeMasker.maskDigitRuns("۴۸۲۹۱۳ and 48‌2913 but 123 and 123456789"),
        )

    @Test fun codesWrittenInGroupsAreHidden() =
        assertEquals(
            "code ****, pin ****, رمز ****",
            CodeMasker.maskSpacedCodes("code 48 29 13, pin 4829-13, رمز ۴۸ ۲۹ ۱۳"),
        )

    @Test fun longNumbersInGroupsAndSingleGroupsStay() {
        val text = "Call 0712 123 4567, ref 7766554, room 12 3"
        assertEquals(text, CodeMasker.maskSpacedCodes(text))
    }

    @Test fun forwardMaskHidesASpacedCodeNextToALocatedOne() =
        assertEquals(
            "bank verification code is **** ref ****",
            CodeMasker.maskForForward("bank verification code is 48 29 13 ref 7766554"),
        )

    @Test fun forwardMaskHidesAHyphenatedCodeWhole() {
        assertEquals("bank code **** ok", CodeMasker.maskForForward("bank code 4829-13 ok"))
        assertEquals("رمز **** است", CodeMasker.maskForForward("رمز ۴۸۲۹-۱۳ است"))
    }
}
