package com.glooboo.umessage.ui.log

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.SpamLog
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.ui.settings.SimCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** SE-38 / ID-39: the filter row above the log. Forwards and errors show only under [ALL]. */
enum class LogFilter { ALL, BLOCKED, ALLOWED }

internal fun LogFilter.accepts(entry: SpamLogEntry): Boolean = when (this) {
    LogFilter.ALL -> true
    LogFilter.BLOCKED -> entry.decision == "BLOCK"
    LogFilter.ALLOWED -> entry.decision == "ALLOW"
}

/**
 * One row of the log: the newest entry plus the identical decisions for the same sender right below it (SE-41), so
 * "+1 5000 334, Blocked, 3 times" reads as one line. [entries] is newest first and never empty.
 */
data class LogRowGroup(val entries: List<SpamLogEntry>) {
    val newest: SpamLogEntry get() = entries.first()
    val count: Int get() = entries.size

    /** The newest fix of any entry in the row, empty when none was fixed. */
    val fix: String get() = entries.firstOrNull { it.fix.isNotEmpty() }?.fix.orEmpty()

    /** Stable across reloads: the newest entry's time and sender. */
    val key: String get() = fixKey(newest)
}

/** One day of the log under its own header (SE-37). */
data class LogDay(val day: LocalDate, val rows: List<LogRowGroup>)

/** Two entries collapse into one row when they say the same thing about the same sender on the same SIM. */
internal fun sameDecision(a: SpamLogEntry, b: SpamLogEntry): Boolean =
    a.sender == b.sender && a.decision == b.decision && a.reason == b.reason && a.ruleId == b.ruleId &&
        a.subscriptionId == b.subscriptionId

/**
 * Filters [entries] (newest first), groups them by local day and collapses runs of the same decision for the same
 * sender within a day. Entries a user fixed start their own row, so the "Fixed" mark never hides behind a count.
 */
internal fun groupLog(
    entries: List<SpamLogEntry>,
    filter: LogFilter = LogFilter.ALL,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LogDay> {
    val days = LinkedHashMap<LocalDate, MutableList<LogRowGroup>>()
    for (entry in entries) {
        if (!filter.accepts(entry)) continue
        val day = Instant.ofEpochMilli(entry.time).atZone(zone).toLocalDate()
        val rows = days.getOrPut(day) { mutableListOf() }
        val last = rows.lastOrNull()
        if (last != null && collapses(last.entries.last(), entry)) {
            rows[rows.lastIndex] = LogRowGroup(last.entries + entry)
        } else {
            rows += LogRowGroup(listOf(entry))
        }
    }
    return days.map { (day, rows) -> LogDay(day, rows) }
}

private fun collapses(previous: SpamLogEntry, entry: SpamLogEntry): Boolean =
    sameDecision(previous, entry) && previous.fix.isEmpty() && entry.fix.isEmpty()

/** What the details sheet can do with a row; every action closes the sheet first. */
internal class DecisionSheetActions(
    val onFix: (allow: Boolean) -> Unit,
    val onRestore: (BlockedMessage) -> Unit,
)

/** Key of a log entry for the "fixed" mark; the log has no ids. */
internal fun fixKey(entry: SpamLogEntry): String = "${entry.time}|${entry.sender}"

/** Only plain allow/block decisions of a known sender can be fixed with a sender rule (not errors or forwards). */
internal fun canFix(entry: SpamLogEntry): Boolean =
    (entry.decision == "BLOCK" || entry.decision == "ALLOW") && entry.sender.isNotBlank()

/**
 * B4: the slot ("SIM 2") of the SIM that took the message, or null when it should not be shown: on a single-SIM
 * phone, without the phone permission (no SIMs known), for a message with no SIM, or for a SIM no longer in the phone.
 * The subscription id is never shown: on real phones it is often 3, 4 or more.
 */
internal fun simSlot(subscriptionId: Int, sims: List<SimCard>): Int? {
    if (sims.size < 2 || subscriptionId < 0) return null
    return sims.firstOrNull { it.subscriptionId == subscriptionId }?.slot
}

/** How far apart the log time and the blocked message's received time may be (the network's time can lag). */
internal const val RESTORE_MATCH_WINDOW_MS = 15 * 60 * 1000L

/**
 * The blocked message a BLOCK entry stands for, so the sheet can offer "Restore message": same sender, received
 * closest to the decision time within [RESTORE_MATCH_WINDOW_MS]. Null when it is no longer in Blocked (restored,
 * deleted or purged) or the entry is not a block.
 */
internal fun matchBlocked(entry: SpamLogEntry, blocked: List<BlockedMessage>): BlockedMessage? {
    if (entry.decision != "BLOCK" || entry.fix == SpamLog.FIX_RESTORED) return null
    val sender = entry.sender.trim()
    if (sender.isEmpty()) return null
    return blocked
        .filter { it.address.trim() == sender && abs(it.receivedAt - entry.time) <= RESTORE_MATCH_WINDOW_MS }
        .minByOrNull { abs(it.receivedAt - entry.time) }
}
