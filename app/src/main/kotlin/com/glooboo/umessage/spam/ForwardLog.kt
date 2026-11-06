package com.glooboo.umessage.spam

import kotlinx.coroutines.flow.Flow

/**
 * Where a forward stands. PENDING is only the moment between writing the row and the hand-over to the radio (a row
 * left PENDING means the process ended in between). SENT means the radio accepted it; a failure it reports later flips
 * the row to FAILED. SKIPPED is a forward a limit stopped (the daily limit or the hourly cap of a rule): nothing was
 * sent, and the row says so in the Forwarded tab. Its [ForwardLogEntry.errorCode] names the limit.
 */
enum class ForwardStatus { PENDING, SENT, FAILED, SKIPPED }

/**
 * One forward to one target. Holds no message text: the original is looked up again by sender and time when it is
 * shown or retried.
 */
data class ForwardLogEntry(
    val id: Long,
    val at: Long,
    val updatedAt: Long,
    val ruleId: Long,
    val sourceAddress: String,
    val sourceDate: Long,
    val sourceIsMms: Boolean,
    val target: String,
    /** The SIM the forward went out on; null = the phone's default SMS SIM. */
    val subscriptionId: Int?,
    val status: ForwardStatus,
    val errorCode: String?,
    val attempts: Int,
)

/** Read side of the forward log for the Forwarded tab. */
interface ForwardLogRepository {
    /** Newest first. */
    val entries: Flow<List<ForwardLogEntry>>

    /** Number of failed forwards that last changed after [since]. */
    fun failedSince(since: Long): Flow<Int>
}

object ForwardLogPolicy {
    /** Rows older than this are removed. */
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    const val MAX_ROWS = 300

    /** Fewer digits than this is not enough to call two numbers the same by their ending. */
    private const val MIN_SUFFIX = 7

    /** A mobile number without its country code or leading zero. */
    private const val NATIONAL = 10

    /** Same phone number written differently ("+1 555 000 1111" and "5550001111"), or the same sender id. */
    fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        if (da == db) return true
        if (minOf(da.length, db.length) >= MIN_SUFFIX && (da.endsWith(db) || db.endsWith(da))) return true
        // 05551234567 and +15551234567: a national leading zero against a country code share their last ten digits.
        return da.length >= NATIONAL && db.length >= NATIONAL && da.takeLast(NATIONAL) == db.takeLast(NATIONAL)
    }

    /**
     * Whether [entry] used a send at or after [since]: it was made then, or retried then. Skipped forwards never count.
     * The hourly cap counts failed sends too (they were tried); the daily limit does not (a failed SMS cost nothing).
     */
    fun sentSince(entry: ForwardLogEntry, since: Long): Boolean =
        entry.status != ForwardStatus.SKIPPED && (entry.at >= since || (entry.attempts > 1 && entry.updatedAt >= since))

    /** Sends each rule made at or after [since] (any status but skipped), for the hourly cap. */
    fun countsByRule(rows: List<ForwardLogEntry>, since: Long): Map<Long, Int> =
        rows.filter { sentSince(it, since) }.groupingBy { it.ruleId }.eachCount()

    /** Sends at or after [since] that were not reported failed, for the daily limit. */
    fun sentCount(rows: List<ForwardLogEntry>, since: Long): Int =
        rows.count { sentSince(it, since) && it.status != ForwardStatus.FAILED }

    /** Error code of a skipped row: the daily limit stopped it. */
    const val SKIP_DAILY_LIMIT = "DAILY_LIMIT"

    /** Error code of a skipped row: the rule's hourly cap stopped it. */
    const val SKIP_RATE_LIMITED = "RATE_LIMITED"
}
