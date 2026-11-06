package com.glooboo.umessage.spam.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForwardRateLimitTest {

    private fun send(rule: Long, target: String = "+15550001111") = PlannedForward(rule, target, "text", null)

    @Test
    fun underTheCapEverythingIsAllowed() {
        val (allowed, limited) = ForwardRateLimit.split(listOf(send(1), send(1, "+15550002222")), emptyMap(), max = 3)
        assertThat(allowed).hasSize(2)
        assertThat(limited).isEmpty()
    }

    @Test
    fun sendsOverTheCapAreLimitedPerRule() {
        val sends = listOf(send(1), send(1, "+15550002222"), send(2))
        val (allowed, limited) = ForwardRateLimit.split(sends, mapOf(1L to 1), max = 2)
        assertThat(allowed.map { it.ruleId }).containsExactly(1L, 2L).inOrder()
        assertThat(limited.map { it.target }).containsExactly("+15550002222")
    }

    @Test
    fun aRuleAtTheCapIsFullyLimited() {
        val (allowed, limited) =
            ForwardRateLimit.split(listOf(send(1)), mapOf(1L to ForwardRateLimit.MAX_PER_RULE_PER_HOUR))
        assertThat(allowed).isEmpty()
        assertThat(limited).hasSize(1)
    }
}
