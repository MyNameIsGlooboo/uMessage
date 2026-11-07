package com.glooboo.umessage.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** D14: the export header lists the offsets the rows can carry, not only today's. */
class ZoneOffsetsTest {

    private fun millis(date: String, zone: ZoneId) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun aRangeAcrossTheSummerChangeListsBothOffsets() {
        val zone = ZoneId.of("Europe/London")

        val offsets = ZoneOffsets.between(zone, millis("2024-02-01", zone), millis("2024-08-01", zone))

        assertThat(offsets).containsExactly(ZoneOffset.UTC, ZoneOffset.ofHours(1)).inOrder()
        assertThat(ZoneOffsets.text(offsets)).isEqualTo("UTC, UTC+01:00")
    }

    @Test
    fun aRangeInsideOneSeasonHasOneOffset() {
        val zone = ZoneId.of("Europe/London")

        val offsets = ZoneOffsets.between(zone, millis("2024-06-01", zone), millis("2024-07-01", zone))

        assertThat(offsets).containsExactly(ZoneOffset.ofHours(1))
    }

    @Test
    fun aZoneWithoutDaylightTimeHasOneOffset() {
        val zone = ZoneId.of("Asia/Kolkata")

        assertThat(ZoneOffsets.between(zone, 0L, millis("2024-01-01", zone)))
            .containsExactly(ZoneOffset.ofHoursMinutes(5, 30))
    }
}
