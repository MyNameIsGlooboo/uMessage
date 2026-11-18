package com.glooboo.umessage.ui.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BR-36 and H5: a national number gets the country code of the phone's region; with no region nothing is rewritten. */
class ForwardTargetStoredTest {

    @Test
    fun localMobileGetsTheCountryCodeOfTheRegion() {
        assertThat(ForwardTargets.stored("0900 000 1111", "IR")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("۰۹۰۰۰۰۰۱۱۱۱", "IR")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("0712 345 6789", "GB")).isEqualTo("+447123456789")
    }

    @Test
    fun aNumberOfAnotherRegionIsNeverRewrittenToTheDevicesCountry() {
        // 09… is not a valid national number in the US, so it stays as typed.
        assertThat(ForwardTargets.stored("09000001111", "US")).isEqualTo("09000001111")
    }

    @Test
    fun withNoRegionNothingIsRewritten() {
        assertThat(ForwardTargets.stored("09000001111")).isEqualTo("09000001111")
        assertThat(ForwardTargets.stored("09000001111", null)).isEqualTo("09000001111")
        assertThat(ForwardTargets.stored("09000001111", "")).isEqualTo("09000001111")
    }

    @Test
    fun internationalAndOtherNumbersAreKept() {
        assertThat(ForwardTargets.stored("+989000001111", "IR")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("+989000001111", "US")).isEqualTo("+989000001111")
        assertThat(ForwardTargets.stored("+1 555 000 1111", "IR")).isEqualTo("+15550001111")
        assertThat(ForwardTargets.stored("1234567", "IR")).isEqualTo("1234567")
    }

    @Test
    fun notANumberStaysNull() {
        assertThat(ForwardTargets.stored("abc", "IR")).isNull()
        assertThat(ForwardTargets.stored("abc")).isNull()
    }

    @Test
    fun theFormStoresWithItsRegion() {
        val rule = ForwardForm(targets = listOf("0900 000 1111"), region = "IR")
        assertThat(rule.cleanTargets).containsExactly("+989000001111")
        assertThat(ForwardForm(targets = listOf("0900 000 1111")).cleanTargets).containsExactly("09000001111")
    }
}
