package com.glooboo.umessage.ui.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BR-36: a local 09… mobile gets the +98 code (the built-in region of ForwardTargets); other numbers stay as typed. */
class ForwardTargetStoredTest {

    @Test
    fun localMobileGetsCountryCode() {
        assertThat(ForwardTargets.stored("0900 000 1111")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("۰۹۰۰۰۰۰۱۱۱۱")).isEqualTo("+989000001111")
    }

    @Test
    fun internationalAndOtherNumbersAreKept() {
        assertThat(ForwardTargets.stored("+989000001111")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("+1 555 000 1111")).isEqualTo("+15550001111")
        assertThat(ForwardTargets.stored("1234567")).isEqualTo("1234567")
    }

    @Test
    fun notANumberStaysNull() {
        assertThat(ForwardTargets.stored("abc")).isNull()
    }
}
