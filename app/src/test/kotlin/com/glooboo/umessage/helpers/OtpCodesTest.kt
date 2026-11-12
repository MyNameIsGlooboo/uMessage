package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.engine.OtpDetector
import com.glooboo.umessage.ui.thread.CopyKind
import com.glooboo.umessage.ui.thread.CopyToken
import com.glooboo.umessage.ui.thread.detectCopyTokens
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one rule: the spam filter, the notification and the list ([OtpCodeExtractor]), the chat chips
 * ([detectCopyTokens]), the categories and the masker all agree on what a code is and what value it has.
 */
class OtpCodesTest {

    private val positives = listOf(
        "Your OTP is 4821",
        "رمز پویا: ۱۲۳۴۵",
        "Password: x7Kq2m",
        "123456 is your login code",
        "5521 is your sign-in number",
        "Your code: 482 913",
    )

    private val negatives = listOf(
        "Meet at 1830",
        "Promo code SAVE20 50% off",
        "Your order 123456 ships tomorrow",
        "username: sara22",
        "Barcode: 123456 on the box",
        "code: 1500 ریال",
    )

    private fun agree(body: String): List<Boolean> = listOf(
        OtpDetector.looksLikeOtp(body),
        OtpCodes.looksLikeCode(body),
        OtpCodeExtractor.extract(body) != null,
        detectCopyTokens(body).any { it.kind != CopyKind.Username },
        MessageCategories.classify("BankX", body, isContact = false) == MessageCategory.Other,
    )

    @Test
    fun everyEntryPointCallsTheSameMessagesCodes() {
        positives.forEach { assertThat(agree(it)).containsExactly(true, true, true, true, true) }
    }

    @Test
    fun everyEntryPointCallsTheSameMessagesNoCode() {
        for (body in negatives) {
            assertThat(agree(body).take(4)).containsExactly(false, false, false, false)
        }
    }

    @Test
    fun aLabelledPasswordIsACodeForTheFilter() {
        assertThat(OtpDetector.looksLikeOtp("Your password: x7Kq2m")).isTrue()
        assertThat(OtpCodes.primary("Your password: x7Kq2m")).isEqualTo(CodeToken(CodeKind.Password, "x7Kq2m"))
        assertThat(OtpCodeExtractor.extract("Your password: x7Kq2m")).isEqualTo("x7Kq2m")
    }

    @Test
    fun aUsernameIsOfferedButIsNoCode() {
        assertThat(OtpCodes.tokens("username: sara22")).containsExactly(CodeToken(CodeKind.Username, "sara22"))
        assertThat(OtpCodes.primary("username: sara22")).isNull()
        assertThat(OtpDetector.looksLikeOtp("username: sara22")).isFalse()
    }

    @Test
    fun theCodeBeatsThePasswordAsThePrimaryValue() {
        assertThat(OtpCodes.primary("password: abc12\ncode: 482913")?.value).isEqualTo("482913")
    }

    // Persian digits: decided once, a value is Latin everywhere (copy, chip, mask), whatever the message used.
    @Test
    fun persianAndArabicDigitsAreCopiedAsLatinDigitsEverywhere() {
        val persian = "کد تایید شما: ۴۸۲۹۱۳"
        val arabic = "رمز التحقق ٤٨٢٩١٣"
        for (body in listOf(persian, arabic)) {
            assertThat(OtpCodeExtractor.extract(body)).isEqualTo("482913")
            assertThat(detectCopyTokens(body)).containsExactly(CopyToken(CopyKind.Code, "482913"))
        }
        assertThat(detectCopyTokens("رمز: ۱۲۳۴")).containsExactly(CopyToken(CopyKind.Code, "1234"))
    }

    @Test
    fun aLabelledCodeWrittenInGroupsIsOneValue() {
        assertThat(OtpCodes.primary("Code: 482 913")?.value).isEqualTo("482913")
        assertThat(OtpCodes.primary("Code: 482-913")?.value).isEqualTo("482913")
    }

    @Test
    fun aLabelledNumberMustHaveTheShapeOfACode() {
        assertThat(OtpCodes.tokens("code: 12 and pin: 123")).isEmpty()
        assertThat(OtpCodes.tokens("code: 123456789012")).isEmpty()
    }

    @Test
    fun anAdvertisedCodeIsNoCode() {
        assertThat(OtpCodes.tokens("Discount code: SAVE20, 50% off today")).isEmpty()
        assertThat(OtpDetector.looksLikeOtp("Discount code: 7788, valid this week only")).isFalse()
    }

    @Test
    fun theCodeNextToAKeywordIsAddedWhenOnlyAPasswordIsLabelled() {
        val tokens = OtpCodes.tokens("Login password: abc12. Your verification code is 482913.")
        assertThat(tokens.map { it.value }).containsExactly("abc12", "482913").inOrder()
    }

    @Test
    fun theExtraKeywordsOfTheOldExtractorStillWork() {
        assertThat(OtpCodeExtractor.extract("Your passcodes: 4821")).isEqualTo("4821")
        assertThat(OtpCodeExtractor.extract("Sign in with 5521")).isEqualTo("5521")
        assertThat(OtpCodeExtractor.extract("Log-in number 7788 expires")).isEqualTo("7788")
    }

    @Test
    fun aCodeTooFarFromItsKeywordIsNoCodeButForwardingKeepsItsWiderNet() {
        val far = "Verification is needed for your new account at our branch downtown, bring id, ref 7766554"
        assertThat(OtpCodes.looksLikeCode(far)).isFalse()
        assertThat(OtpCodeExtractor.hasKeywordNearNumber(far)).isTrue()
    }

    @Test
    fun theMaskerHidesTheSameValueThatIsCopied() {
        assertThat(CodeMasker.mask("Your password: x7Kq2m")).isEqualTo("Your password: ••••")
        assertThat(CodeMasker.mask("کد تایید: ۴۸۲۹۱۳ را به کسی ندهید")).isEqualTo("کد تایید: •••• را به کسی ندهید")
        assertThat(CodeMasker.mask("Code: 482 913, again 482913", mark = "****")).isEqualTo("Code: ****, again ****")
    }

    @Test
    fun maskIfOnlyMasksWhenTheSettingIsOn() {
        assertThat(CodeMasker.maskIf(true, "Your code is 482913")).isEqualTo("Your code is ••••")
        assertThat(CodeMasker.maskIf(false, "Your code is 482913")).isEqualTo("Your code is 482913")
        assertThat(CodeMasker.maskIf(true, "Meet at 1830")).isEqualTo("Meet at 1830")
    }

    @Test
    fun tokensHoldEveryCodeInOrderAndPrimaryIsTheFirst() {
        val body = "Use 333333 to log in, or 444444 to reset your password"
        assertThat(OtpCodes.tokens(body).map { it.value }).containsExactly("333333", "444444").inOrder()
        assertThat(OtpCodes.primary(body)).isEqualTo(CodeToken(CodeKind.Code, "333333"))
        assertThat(OtpCodeExtractor.extract(body)).isEqualTo("333333")
    }

    @Test
    fun anAlphanumericCodeNextToAKeywordIsCopiedAndMasked() {
        val body = "Your code is ab12cd"
        assertThat(OtpCodes.looksLikeCode(body)).isTrue()
        assertThat(OtpCodes.primary(body)).isEqualTo(CodeToken(CodeKind.Code, "ab12cd"))
        assertThat(OtpCodeExtractor.extract(body)).isEqualTo("ab12cd")
        assertThat(detectCopyTokens(body)).containsExactly(CopyToken(CopyKind.Code, "ab12cd"))
        assertThat(CodeMasker.mask(body)).isEqualTo("Your code is ••••")
        assertThat(CodeMasker.maskForForward(body)).isEqualTo("Your code is ****")
    }

    @Test
    fun anAlphanumericCodeIsOnlyOfferedWithAKeywordInItsSentence() {
        assertThat(OtpCodes.tokens("Your order ab12cd has shipped")).isEmpty()
        assertThat(OtpCodes.tokens("Order a1b2c3. Verify your email address in the app")).isEmpty()
        assertThat(OtpCodes.looksLikeCode("Promo code ab12cd")).isFalse()
    }

    @Test
    fun aDigitCodeAndAnAlphanumericOneInOneMessageAreBothOffered() {
        val tokens = OtpCodes.tokens("Use 333333 to log in. Your OTP is ab12cd")
        assertThat(tokens.map { it.value }).containsExactly("333333", "ab12cd").inOrder()
    }

    @Test
    fun aNumberWithoutAKeywordNextToItIsNoSecondCode() {
        assertThat(OtpCodes.tokens("Your OTP is 4821. Ref 998877 for support").map { it.value })
            .containsExactly("4821")
    }
}
