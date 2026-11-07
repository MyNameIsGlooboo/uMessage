package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** B12: dates past 2038 stop at the last Int second instead of wrapping into 1901. */
class SecondsIntTest {

    @Test
    fun aDatePast2038StopsAtTheLastIntSecond() {
        val year2040 = 2_208_988_800L // seconds
        assertThat(year2040.toSecondsInt()).isEqualTo(Int.MAX_VALUE)
        assertThat(year2040.toInt()).isLessThan(0) // what toInt() would have done
    }

    @Test
    fun anOrdinaryDateIsUnchanged() {
        assertThat(1_700_000_000L.toSecondsInt()).isEqualTo(1_700_000_000)
    }

    @Test
    fun theRetentionCutoffDoesNotWrapPast2038() {
        val farFuture = 2_300_000_000_000L // ms, year 2042
        assertThat(MessageRetention.cutoffSeconds(farFuture, 30)).isEqualTo(Int.MAX_VALUE)
    }
}
