package com.glooboo.umessage.spam.engine

import java.time.Instant
import java.time.ZoneId

/**
 * ID-06: a cap on forwards per day over all rules, so a noisy sender and an "Any" rule cannot drain prepaid credit.
 * Pure: the caller supplies how many forwards were already made today (counted from the forward log, no extra storage).
 * It adds to the per-rule hourly cap of [ForwardRateLimit], which stays.
 */
object ForwardDailyLimit {

    /**
     * Splits [sends] into those still allowed today and those over [limit], in order. [sentToday] counts the forwards
     * made since local midnight. A [limit] of 0 or less means no limit.
     */
    fun split(
        sends: List<PlannedForward>,
        sentToday: Int,
        limit: Int,
    ): Pair<List<PlannedForward>, List<PlannedForward>> {
        if (limit <= 0) return sends to emptyList()
        val room = (limit - sentToday).coerceAtLeast(0)
        return sends.take(room) to sends.drop(room)
    }

    /** Local midnight at or before [now], in epoch milliseconds. */
    fun startOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** The local calendar day as an epoch day, to tell whether the "limit reached" notice was already shown today. */
    fun dayNumber(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
}
