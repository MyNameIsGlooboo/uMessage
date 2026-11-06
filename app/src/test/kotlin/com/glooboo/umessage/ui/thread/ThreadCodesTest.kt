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
}
