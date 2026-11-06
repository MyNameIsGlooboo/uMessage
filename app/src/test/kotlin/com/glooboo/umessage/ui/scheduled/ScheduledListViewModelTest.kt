package com.glooboo.umessage.ui.scheduled

import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.RepeatRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** I25: the order and the sections of the scheduled messages list. */
class ScheduledListViewModelTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(day: Int, hour: Int) =
        LocalDate.of(2026, 5, day).atTime(hour, 0).atZone(utc).toInstant().toEpochMilli()

    private fun row(id: Long, day: Int, hour: Int, held: Boolean = false, repeat: RepeatRule = RepeatRule.NONE) =
        ScheduledRow(id, id, "Sara", "text $id", millis(day, hour), held, repeat, approximate = false)

    @Test
    fun rowsAreSortedByTimeAndGroupedByDay() {
        val state = groupScheduled(listOf(row(1, 12, 18), row(2, 10, 9), row(3, 12, 8)), utc)

        assertThat(state.days.map { it.date })
            .containsExactly(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 12)).inOrder()
        assertThat(state.days[1].rows.map { it.id }).containsExactly(3L, 1L).inOrder()
        assertThat(state.count).isEqualTo(3)
        assertThat(state.loading).isFalse()
    }

    @Test
    fun messagesThatWereNotSentHaveTheirOwnList() {
        val state = groupScheduled(listOf(row(1, 12, 9), row(2, 3, 9, held = true), row(3, 2, 9, held = true)), utc)

        assertThat(state.notSent.map { it.id }).containsExactly(3L, 2L).inOrder()
        assertThat(state.days.flatMap { it.rows }.map { it.id }).containsExactly(1L)
        assertThat(state.count).isEqualTo(3)
    }

    @Test
    fun aMessageNearMidnightBelongsToItsDayOnThePhone() {
        val berlin = ZoneId.of("Europe/Berlin")
        val late = LocalDate.of(2026, 5, 10).atTime(23, 30).atZone(berlin).toInstant().toEpochMilli()
        val state = groupScheduled(listOf(row(1, 10, 9).copy(millis = late)), berlin)

        assertThat(state.days.single().date).isEqualTo(LocalDate.of(2026, 5, 10))
    }

    @Test
    fun nothingScheduledIsEmptyAndLoaded() {
        val state = groupScheduled(emptyList(), utc)

        assertThat(state.count).isEqualTo(0)
        assertThat(state.loading).isFalse()
    }

    @Test
    fun theRepeatNoteNamesTheRuleAndAHeldRepeatIsPaused() {
        assertThat(repeatNoteRes(RepeatRule.NONE, held = false)).isNull()
        assertThat(repeatNoteRes(RepeatRule.YEARLY, held = false)).isEqualTo(R.string.scheduled_repeats_yearly)
        assertThat(repeatNoteRes(RepeatRule.DAILY, held = true)).isEqualTo(R.string.scheduled_repeats_paused)
    }
}
