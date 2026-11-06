package com.glooboo.umessage.spam.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I24: which senders count as "from another country" for a chosen home country. */
class RegionBlockTest {

    private fun foreign(address: String, home: String = "GB") = RegionBlock.isForeign(address, home)

    @Test
    fun homeNumbersInEveryWritingAreNotForeign() {
        assertThat(foreign("+447121234567")).isFalse()
        assertThat(foreign("00447121234567")).isFalse()
        assertThat(foreign("+44 712 123 4567")).isFalse()
        assertThat(foreign("+۴۴ ۷۱۲۱۲۳۴۵۶۷")).isFalse()
    }

    @Test
    fun nationalNumbersAreTheHomeCountrys() {
        assertThat(foreign("07121234567")).isFalse()
        assertThat(foreign("7123456789")).isFalse()
    }

    @Test
    fun numbersWithAnotherCountryCodeAreForeign() {
        assertThat(foreign("+919876543210")).isTrue()
        assertThat(foreign("00919876543210")).isTrue()
        assertThat(foreign("+14155550133")).isTrue()
        assertThat(foreign("+4915112345678")).isTrue()
    }

    @Test
    fun shortCodesAndSenderNamesAreNotForeign() {
        assertThat(foreign("30001234")).isFalse()
        assertThat(foreign("100")).isFalse()
        assertThat(foreign("AX-HDFCBK")).isFalse()
        assertThat(foreign("Bank")).isFalse()
    }

    @Test
    fun unreadableValuesAreLeftAlone() {
        assertThat(foreign("")).isFalse()
        assertThat(foreign("   ")).isFalse()
        assertThat(foreign("+")).isFalse()
        assertThat(foreign("+1")).isFalse()
        assertThat(foreign("+999123")).isFalse()
    }

    @Test
    fun sharedCountryCodeCountsAsHome() {
        assertThat(foreign("+14155550133", home = "US")).isFalse()
        assertThat(foreign("+14165550133", home = "CA")).isFalse()
        assertThat(foreign("+447121234567", home = "US")).isTrue()
    }

    @Test
    fun badHomeRegionNeverBlocks() {
        assertThat(foreign("+919876543210", home = "")).isFalse()
        assertThat(foreign("+919876543210", home = "ZZ")).isFalse()
        assertThat(foreign("+919876543210", home = "gb")).isTrue()
    }
}
