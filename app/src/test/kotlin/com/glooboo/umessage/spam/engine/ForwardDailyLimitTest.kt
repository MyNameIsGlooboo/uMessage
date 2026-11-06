package com.glooboo.umessage.spam.engine

import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import org.junit.Test

class ForwardDailyLimitTest {
    private fun sends(n: Int) = (1..n).map { PlannedForward(1, "+1555000$it", "x", null) }

    @Test fun noLimitAllowsAll() {
        val (allowed, over) = ForwardDailyLimit.split(sends(5), sentToday = 100, limit = 0)
        assertThat(allowed).hasSize(5)
        assertThat(over).isEmpty()
    }

    @Test fun cutsAtTheLimit() {
        val (allowed, over) = ForwardDailyLimit.split(sends(5), sentToday = 28, limit = 30)
        assertThat(allowed).hasSize(2)
        assertThat(over).hasSize(3)
    }

    @Test fun limitReachedAllowsNone() {
        val (allowed, over) = ForwardDailyLimit.split(sends(2), sentToday = 40, limit = 30)
        assertThat(allowed).isEmpty()
        assertThat(over).hasSize(2)
    }

    @Test fun startOfDayIsLocalMidnight() {
        val noon = 12 * 60 * 60 * 1000L + 20_000L * 24 * 60 * 60 * 1000L
        assertThat(ForwardDailyLimit.startOfDay(noon, ZoneOffset.UTC)).isEqualTo(20_000L * 24 * 60 * 60 * 1000L)
        assertThat(ForwardDailyLimit.dayNumber(noon, ZoneOffset.UTC)).isEqualTo(20_000L)
    }
}
