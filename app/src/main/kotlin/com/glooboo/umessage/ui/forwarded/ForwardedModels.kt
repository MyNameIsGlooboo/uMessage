package com.glooboo.umessage.ui.forwarded

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardLogPolicy
import com.glooboo.umessage.spam.engine.ForwardBudget
import com.glooboo.umessage.spam.engine.ForwardDailyLimit
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.ui.spam.SimInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Forwarded tab shows. */
data class ForwardedUiState(
    val loading: Boolean = true,
    val rules: List<ForwardRule> = emptyList(),
    /** Newest first. */
    val entries: List<ForwardLogEntry> = emptyList(),
    val sims: List<SimInfo> = emptyList(),
    /** The global "pause all forwarding" switch. */
    val paused: Boolean = false,
    /** Contact name per number; empty text = no contact. */
    val names: Map<String, String> = emptyMap(),
    /** A retry is running. */
    val busy: Boolean = false,
    /** The daily forward limit (0 = none). */
    val dailyLimit: Int = 0,
) {
    val failed: List<ForwardLogEntry> get() = entries.filter { it.status == ForwardStatus.FAILED }
    val enabledRules: Int get() = rules.count { it.enabled }
    val dualSim: Boolean get() = sims.size > 1

    /** The paid-SMS note is shown only until the first forward went out. */
    val showPaidNote: Boolean get() = entries.none { it.status == ForwardStatus.SENT }

    /** Forwards counted against the daily limit today. */
    fun sentToday(now: Long): Int = ForwardLogPolicy.sentCount(entries, ForwardDailyLimit.startOfDay(now))

    /** Bug 9: the daily limit is set and reached, so nothing more goes out today. */
    fun limitReached(now: Long): Boolean = ForwardBudget.dailyLimitReached(entries, now, dailyLimit)

    /** What the header card says. The pause switch wins, then "no rule is on", then the daily limit. */
    fun header(now: Long): ForwardedHeader = when {
        paused -> ForwardedHeader.PAUSED
        enabledRules == 0 -> ForwardedHeader.NO_RULE
        limitReached(now) -> ForwardedHeader.LIMIT
        else -> ForwardedHeader.ON
    }

    /** The failed forwards "Retry all" sends again: the last day's only (M2). */
    fun retryAllIds(now: Long): List<Long> = ForwardBudget.retryAllIds(entries, now)

    /**
     * What sending [ids] again would really do now, for the "Send again?" dialog: the same checks as each retry
     * ([ForwardBudget.retryBlock]), in order, with every retry that goes out counted against the limits of the next.
     */
    fun retryPreview(ids: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault()): RetryPreview {
        val rows = entries.toMutableList()
        val byRule = rules.associateBy { it.id }
        var preview = RetryPreview()
        for (id in ids) {
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) continue
            val row = rows[index]
            preview = when (ForwardBudget.retryBlock(row, byRule[row.ruleId], paused, rows, now, dailyLimit, zone)) {
                ForwardBudget.RetryBlock.PAUSED -> preview.copy(paused = preview.paused + 1)
                ForwardBudget.RetryBlock.RULE_OFF -> preview.copy(ruleOff = preview.ruleOff + 1)
                ForwardBudget.RetryBlock.LIMIT -> preview.copy(limited = preview.limited + 1)
                null -> {
                    rows[index] = row.copy(status = ForwardStatus.PENDING, attempts = row.attempts + 1, updatedAt = now)
                    preview.copy(willSend = preview.willSend + 1)
                }
            }
        }
        return preview
    }

    fun nameOf(address: String): String = names[address].orEmpty().ifEmpty { address }
}

/** How many of the forwards to send again will go out, and how many each check stops. */
data class RetryPreview(val willSend: Int = 0, val paused: Int = 0, val ruleOff: Int = 0, val limited: Int = 0)

/** The state the Forwarded tab's header card shows. */
enum class ForwardedHeader { ON, PAUSED, NO_RULE, LIMIT }

sealed interface ForwardedEvent {
    /** What a batch of retries did; the ones not sent are split by why, so the snackbar can name the reason. */
    data class Retried(
        val sent: Int,
        val failed: Int,
        val missing: Int,
        val paused: Int = 0,
        val ruleOff: Int = 0,
        val limited: Int = 0,
    ) : ForwardedEvent
}

data class DayGroup(val day: LocalDate, val entries: List<ForwardLogEntry>)

/** [entries] (newest first) split by local calendar day, keeping their order. */
fun groupByDay(entries: List<ForwardLogEntry>, zone: ZoneId = ZoneId.systemDefault()): List<DayGroup> {
    val groups = LinkedHashMap<LocalDate, MutableList<ForwardLogEntry>>()
    for (entry in entries) {
        val day = Instant.ofEpochMilli(entry.at).atZone(zone).toLocalDate()
        groups.getOrPut(day) { mutableListOf() }.add(entry)
    }
    return groups.map { (day, list) -> DayGroup(day, list) }
}

/** The SIM slot to show for a forward, or null on a single-SIM phone or when it used the phone's default SIM. */
fun simSlotOf(sims: List<SimInfo>, subscriptionId: Int?): Int? {
    if (sims.size < 2 || subscriptionId == null) return null
    return sims.firstOrNull { it.subscriptionId == subscriptionId }?.slot
}

/**
 * Whether the quiet "Forwarded tab added. Hide it?" message is shown: only after the first rule is saved, only if the
 * user has not hidden the tab already, and only once per install.
 */
object ForwardedTabHint {
    fun shouldShow(ruleCountBeforeSave: Int, choice: Boolean?, alreadyShown: Boolean): Boolean =
        ruleCountBeforeSave == 0 && choice != false && !alreadyShown
}
