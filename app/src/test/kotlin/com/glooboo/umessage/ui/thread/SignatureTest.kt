package com.glooboo.umessage.ui.thread

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I60: the signature goes on a new line under a real message, counts toward the SMS parts, and is cleaned. */
class SignatureTest {

    @Test
    fun theSignatureIsAppendedOnceOnANewLine() {
        assertThat(MessageSignature.applyTo("See you at 6", "Sam")).isEqualTo("See you at 6\nSam")
    }

    @Test
    fun anEmptyMessageOrAnEmptySignatureChangesNothing() {
        assertThat(MessageSignature.applyTo("   ", "Sam")).isEqualTo("   ")
        assertThat(MessageSignature.applyTo("Hello", "")).isEqualTo("Hello")
        assertThat(MessageSignature.applyTo("Hello", "  ")).isEqualTo("Hello")
    }

    @Test
    fun theSignatureIsOneLineOfAtMostEightyCharacters() {
        assertThat(MessageSignature.clean("  Sam\nSmith  ")).isEqualTo("Sam Smith")
        assertThat(MessageSignature.clean("x".repeat(200))).hasLength(MessageSignature.MAX_LENGTH)
    }

    @Test
    fun aPersianSignatureIsKeptAsTyped() {
        assertThat(MessageSignature.applyTo("سلام", "سارا")).isEqualTo("سلام\nسارا")
    }

    @Test
    fun theSignatureCountsTowardTheSmsParts() {
        val text = "a".repeat(150)
        val plain = SmsSegments.of(text)
        val signed = SmsSegments.of(MessageSignature.applyTo(text, "Best regards, Sam"))
        assertThat(plain.parts).isEqualTo(1)
        assertThat(signed.parts).isEqualTo(2)
    }
}
