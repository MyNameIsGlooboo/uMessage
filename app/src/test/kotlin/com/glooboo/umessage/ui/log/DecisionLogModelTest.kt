package com.glooboo.umessage.ui.log

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamLog
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.ui.settings.SimCard
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Grouping, filtering, SIM slots and the Restore match of the decision log (SE-37, SE-41, ID-39, B4, SE-38). */
class DecisionLogModelTest {

    private val zone = ZoneOffset.UTC
    private val day2 = LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli()
    private val day1 = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun entry(
        time: Long,
        sender: String,
        decision: String = "BLOCK",
        reason: String = "UNKNOWN_SENDER",
        fix: String = "",
    ) = SpamLogEntry(time, sender, 1, decision, reason, "SMS", fix = fix)

    @Test
    fun repeatedDecisionsOfOneSenderCollapseWithinADay() {
        val log = listOf(
            entry(day2 + 50 * minute, "A"),
            entry(day2 + 40 * minute, "A"),
            entry(day2 + 30 * minute, "A"),
            entry(day2 + 20 * minute, "B"),
            entry(day2 + 10 * minute, "A"),
            // Same sender and decision, but the next day back: a new header and a new row.
            entry(day1 + 60 * minute, "A"),
        )
        val days = groupLog(log, zone = zone)
        assertThat(days.map { it.day }).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)).inOrder()
        assertThat(days[0].rows.map { it.newest.sender to it.count })
            .containsExactly("A" to 3, "B" to 1, "A" to 1).inOrder()
        assertThat(days[1].rows.single().count).isEqualTo(1)
        assertThat(days[0].rows[0].newest.time).isEqualTo(day2 + 50 * minute)
    }

    @Test
    fun differentReasonsOrFixedEntriesStayApart() {
        val log = listOf(
            entry(day2 + 3 * minute, "A"),
            entry(day2 + 2 * minute, "A", reason = "BLOCK_RULE"),
            entry(day2 + minute, "A", reason = "BLOCK_RULE", fix = SpamLog.FIX_ALLOWED),
            entry(day2, "A", reason = "BLOCK_RULE"),
        )
        val rows = groupLog(log, zone = zone).single().rows
        assertThat(rows.map { it.count }).containsExactly(1, 1, 1, 1)
        assertThat(rows[2].fix).isEqualTo(SpamLog.FIX_ALLOWED)
    }

    @Test
    fun filterKeepsOnlyTheChosenDecisions() {
        val log = listOf(
            entry(day2 + 4 * minute, "A"),
            entry(day2 + 3 * minute, "B", decision = "ALLOW", reason = "CONTACT"),
            entry(day2 + 2 * minute, "C", decision = "FORWARD", reason = "SENT"),
            entry(day2 + minute, "D", decision = "ERROR", reason = "X"),
        )
        fun senders(filter: LogFilter) = groupLog(log, filter, zone).flatMap { d -> d.rows.map { it.newest.sender } }
        assertThat(senders(LogFilter.ALL)).containsExactly("A", "B", "C", "D").inOrder()
        assertThat(senders(LogFilter.BLOCKED)).containsExactly("A")
        assertThat(senders(LogFilter.ALLOWED)).containsExactly("B")
        assertThat(groupLog(emptyList(), LogFilter.BLOCKED, zone)).isEmpty()
    }

    @Test
    fun filteringJoinsRunsSplitByAHiddenEntry() {
        val log = listOf(
            entry(day2 + 3 * minute, "A"),
            entry(day2 + 2 * minute, "B", decision = "ALLOW", reason = "CONTACT"),
            entry(day2 + minute, "A"),
        )
        assertThat(groupLog(log, LogFilter.BLOCKED, zone).single().rows.single().count).isEqualTo(2)
        assertThat(groupLog(log, LogFilter.ALL, zone).single().rows).hasSize(3)
    }

    @Test
    fun simSlotIsShownOnlyWithTwoSims() {
        val one = listOf(SimCard(3, 1, "A", null, true))
        val two = one + SimCard(7, 2, "B", null, false)
        assertThat(simSlot(3, one)).isNull()
        assertThat(simSlot(3, emptyList())).isNull()
        // The subscription id (7) is never shown: the slot is.
        assertThat(simSlot(7, two)).isEqualTo(2)
        assertThat(simSlot(3, two)).isEqualTo(1)
        assertThat(simSlot(9, two)).isNull()
        assertThat(simSlot(-1, two)).isNull()
    }

    @Test
    fun restoreFindsTheClosestBlockedMessageOfTheSender() {
        val log = entry(day2 + 10 * minute, "+15550001")
        fun blocked(id: Long, address: String, at: Long) =
            BlockedMessage(id = id, address = address, body = "hi", subscriptionId = 1, isMms = false, receivedAt = at,
                reason = DecisionReason.UNKNOWN_SENDER)
        val list = listOf(
            blocked(1, "+15550001", day2 + 2 * minute),
            blocked(2, "+15550001", day2 + 9 * minute),
            blocked(3, "+15550002", day2 + 10 * minute),
            blocked(4, "+15550001", day2 + 60 * minute),
        )
        assertThat(matchBlocked(log, list)?.id).isEqualTo(2)
        assertThat(matchBlocked(log, list.filter { it.id != 2L })?.id).isEqualTo(1)
        assertThat(matchBlocked(log, listOf(list[2], list[3]))).isNull()
        assertThat(matchBlocked(log.copy(decision = "ALLOW"), list)).isNull()
        assertThat(matchBlocked(log.copy(fix = SpamLog.FIX_RESTORED), list)).isNull()
    }

    @Test
    fun onlyAllowAndBlockOfAKnownSenderCanBeFixed() {
        assertThat(canFix(entry(1, "A"))).isTrue()
        assertThat(canFix(entry(1, "A", decision = "ALLOW"))).isTrue()
        assertThat(canFix(entry(1, " ", decision = "ALLOW"))).isFalse()
        assertThat(canFix(entry(1, "A", decision = "ERROR"))).isFalse()
        assertThat(canFix(entry(1, "A", decision = "FORWARD"))).isFalse()
    }

    @Test
    fun dayKinds() {
        val today = LocalDate.of(2026, 11, 2)
        assertThat(logDayKind(today, today)).isEqualTo(LogDayKind.TODAY)
        assertThat(logDayKind(today.minusDays(1), today)).isEqualTo(LogDayKind.YESTERDAY)
        assertThat(logDayKind(today.minusDays(2), today)).isEqualTo(LogDayKind.OTHER)
        assertThat(logDayKind(today.plusDays(1), today)).isEqualTo(LogDayKind.OTHER)
    }

    @Test
    fun csvQuotesFieldsAndDefusesFormulas() {
        assertThat(DecisionLogCsv.field("plain")).isEqualTo("plain")
        assertThat(DecisionLogCsv.field("a,b")).isEqualTo("\"a,b\"")
        assertThat(DecisionLogCsv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(DecisionLogCsv.field("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)")
        assertThat(DecisionLogCsv.field("@sender")).isEqualTo("'@sender")
        // Phone numbers are the usual sender and stay as they are.
        assertThat(DecisionLogCsv.field("+14155550133")).isEqualTo("+14155550133")
    }

    @Test
    fun csvHasOneLinePerDecisionAndNoMessageText() {
        val log = listOf(
            entry(day2, "+14155550133", fix = SpamLog.FIX_BLOCKED),
            SpamLogEntry(day1, "AX-PROMOS", 7, "BLOCK", "BLOCK_RULE", "SMS", 4, "lottery, win"),
        )
        val csv = DecisionLogCsv.build(log, { if (it.subscriptionId == 7) 2 else null }, zone)
        val lines = csv.trimEnd().split("\r\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo("time,sender,sim,decision,reason,rule,source,fixed")
        assertThat(lines[1]).isEqualTo("2026-11-02T00:00:00Z,+14155550133,,BLOCK,UNKNOWN_SENDER,,SMS,BLOCKED")
        assertThat(lines[2]).isEqualTo("2026-11-01T00:00:00Z,AX-PROMOS,2,BLOCK,BLOCK_RULE,\"lottery, win\",SMS,")
    }
}
