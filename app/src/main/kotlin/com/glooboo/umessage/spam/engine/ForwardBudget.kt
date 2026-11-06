package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardLogPolicy
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import java.time.ZoneId

/**
 * Both cost guards in one place: the hourly cap of each rule ([ForwardRateLimit]) and the daily limit over all rules
 * ([ForwardDailyLimit]), worked out from the forward log. Pure: the caller reads the log, holds the lock that makes
 * "count, decide, write the rows" one step, and sends.
 *
 * Fails closed: when the log could not be read (`rows == null`) nothing is sent, because neither cap can be checked.
 */
object ForwardBudget {

    /** What may go out now, and what each limit stopped. */
    data class Split(
        val allowed: List<PlannedForward>,
        val rateLimited: List<PlannedForward>,
        val overDay: List<PlannedForward>,
    )

    /** The log rows the limits need: everything made or retried since this time. */
    fun since(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        minOf(ForwardDailyLimit.startOfDay(now, zone), now - ForwardRateLimit.WINDOW_MS)

    /**
     * Splits [sends] by the hourly cap first, then by [dailyLimit] (0 = none). [rows] are the log rows made or retried
     * since [since]; null when the log could not be read, which stops every send as rate limited.
     */
    fun split(
        sends: List<PlannedForward>,
        rows: List<ForwardLogEntry>?,
        now: Long,
        dailyLimit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Split {
        if (rows == null) return Split(emptyList(), sends, emptyList())
        val hourly = ForwardLogPolicy.countsByRule(rows, now - ForwardRateLimit.WINDOW_MS)
        val (allowed, limited) = ForwardRateLimit.split(sends, hourly)
        val today = ForwardLogPolicy.sentCount(rows, ForwardDailyLimit.startOfDay(now, zone))
        val (withinDay, overDay) = ForwardDailyLimit.split(allowed, today, dailyLimit)
        return Split(withinDay, limited, overDay)
    }

    /** Why a failed forward may not be sent again now. */
    enum class RetryBlock {
        /** "Pause all forwarding" is on. */
        PAUSED,

        /** Its rule was deleted or turned off. */
        RULE_OFF,

        /** The hourly cap of its rule or the daily limit is reached, or the log could not be read. */
        LIMIT,
    }

    /**
     * Whether a retry of [row] must wait, and why; null when it may go out. A retry is one more paid SMS, so it obeys
     * the same switches and limits as a new forward. [rule] is the row's rule as it is now (null = deleted).
     */
    fun retryBlock(
        row: ForwardLogEntry,
        rule: ForwardRule?,
        paused: Boolean,
        rows: List<ForwardLogEntry>?,
        now: Long,
        dailyLimit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): RetryBlock? {
        if (paused) return RetryBlock.PAUSED
        if (rule == null || !rule.enabled) return RetryBlock.RULE_OFF
        val send = PlannedForward(row.ruleId, row.target, "", row.subscriptionId)
        // The row being retried is not a send of its own yet; it must not count against itself.
        val others = rows?.filter { it.id != row.id }
        return if (split(listOf(send), others, now, dailyLimit, zone).allowed.isEmpty()) RetryBlock.LIMIT else null
    }

    /** Daily limit state for the Forwarded tab: the limit is set and today's forwards reached it. */
    fun dailyLimitReached(
        rows: List<ForwardLogEntry>,
        now: Long,
        dailyLimit: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean =
        dailyLimit > 0 && ForwardLogPolicy.sentCount(rows, ForwardDailyLimit.startOfDay(now, zone)) >= dailyLimit

    /** "Retry all" covers failed forwards made in the last day only; older ones are retried one by one. */
    const val RETRY_ALL_WINDOW_MS = 24L * 60 * 60 * 1000

    /** The failed forwards "Retry all" sends again (newest first, as given). */
    fun retryAllIds(entries: List<ForwardLogEntry>, now: Long): List<Long> =
        entries.filter { it.status == ForwardStatus.FAILED && it.at >= now - RETRY_ALL_WINDOW_MS }.map { it.id }
}
