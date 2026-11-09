package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadCodesTest {
    @Test
    fun findsADigitCodeNextToCodeWords() {
        assertThat(detectCopyTokens("Your verification code is 482913. Do not share it."))
            .containsExactly(CopyToken(CopyKind.Code, "482913"))
    }

    @Test
    fun findsLabelledUsernameAndPassword() {
        val tokens = detectCopyTokens("username: sara22\npassword: x7Kq2m.")
        assertThat(tokens).containsExactly(
            CopyToken(CopyKind.Username, "sara22"),
            CopyToken(CopyKind.Password, "x7Kq2m"),
        ).inOrder()
    }

    @Test
    fun plainNumbersWithoutCodeWordsAreIgnored() {
        assertThat(detectCopyTokens("Your order 123456 ships tomorrow")).isEmpty()
    }

    @Test
    fun labelsMustStartAWord() {
        assertThat(detectCopyTokens("Barcode: 123456 on the box")).isEmpty()
        assertThat(detectCopyTokens("Compass: N45 heading")).isEmpty()
        assertThat(detectCopyTokens("Superuser=abcdef")).isEmpty()
    }

    @Test
    fun aLinkIsNotAUsername() {
        assertThat(detectCopyTokens("Login: https://bank.example/login")).isEmpty()
        assertThat(detectCopyTokens("Login: sara22")).containsExactly(CopyToken(CopyKind.Username, "sara22"))
    }

    @Test
    fun persianLabelsWork() {
        assertThat(detectCopyTokens("رمز: ab12cd")).containsExactly(CopyToken(CopyKind.Code, "ab12cd"))
    }

    @Test
    fun returnsEveryValueWithoutACap() {
        val tokens = detectCopyTokens("username: sara22\npassword: x7Kq2m\ncode: 481516\npin: 2342\nlogin: lost99")
        assertThat(tokens.map { it.value }).containsExactly("sara22", "x7Kq2m", "481516", "2342", "lost99").inOrder()
    }

    @Test
    fun removesRepeatedValues() {
        val tokens = detectCopyTokens("code: 481516 and again code: 481516, password: 481516")
        assertThat(tokens).containsExactly(CopyToken(CopyKind.Code, "481516"))
    }

    @Test
    fun everyReceivedMessageWithAValueGetsChips() {
        val thread = listOf("Your code: 482913", "hello there", "Your code: 771204", "user: bob77 password: hunter22")
        val withChips = thread.map { detectCopyTokens(it).isNotEmpty() }
        assertThat(withChips).containsExactly(true, false, true, true).inOrder()
    }

    @Test
    fun everyCodeOfAMessageGetsAChip() {
        assertThat(detectCopyTokens("Use 333333 to log in, or 444444 to reset your password"))
            .containsExactly(CopyToken(CopyKind.Code, "333333"), CopyToken(CopyKind.Code, "444444")).inOrder()
    }

    @Test
    fun labelledAndPlainCodesAreListedInTextOrder() {
        assertThat(detectCopyTokens("Your code is 482913. Temporary password: x7Kq2m"))
            .containsExactly(CopyToken(CopyKind.Code, "482913"), CopyToken(CopyKind.Password, "x7Kq2m")).inOrder()
    }

    @Test
    fun aRepeatedCodeGetsOneChipAndPersianDigitsComeBackLatin() {
        assertThat(detectCopyTokens("Your code is 482913. Enter 482913 now"))
            .containsExactly(CopyToken(CopyKind.Code, "482913"))
        assertThat(detectCopyTokens("کد: ۱۲۳۴۵۶ و رمز: ۶۵۴۳۲۱"))
            .containsExactly(CopyToken(CopyKind.Code, "123456"), CopyToken(CopyKind.Code, "654321")).inOrder()
    }
}
