package com.glooboo.umessage.spam.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardLogPolicy
import com.glooboo.umessage.spam.ForwardLogRepository
import com.glooboo.umessage.spam.ForwardStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One forward to one target (added in schema 5). No message text is stored. */
@Entity(tableName = "forward_log", indices = [Index("at")])
data class ForwardLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val updatedAt: Long,
    val ruleId: Long,
    val sourceAddress: String,
    val sourceDate: Long,
    val sourceIsMms: Boolean,
    val target: String,
    val subscriptionId: Int?,
    val status: ForwardStatus,
    val errorCode: String?,
    val attempts: Int,
) {
    fun toModel() = ForwardLogEntry(
        id, at, updatedAt, ruleId, sourceAddress, sourceDate, sourceIsMms, target, subscriptionId, status, errorCode,
        attempts,
    )
}

@Dao
interface ForwardLogDao {
    @Insert
    fun insert(entry: ForwardLogEntity): Long

    @Query(
        "UPDATE forward_log SET status = :status, errorCode = :errorCode, attempts = :attempts, updatedAt = :now " +
            "WHERE id = :id",
    )
    fun setResult(id: Long, status: ForwardStatus, errorCode: String?, attempts: Int, now: Long): Int

    @Query("SELECT * FROM forward_log WHERE id = :id")
    fun get(id: Long): ForwardLogEntity?

    @Query("SELECT * FROM forward_log ORDER BY at DESC, id DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<ForwardLogEntity>>

    @Query("SELECT * FROM forward_log WHERE at >= :since ORDER BY at DESC, id DESC")
    fun recent(since: Long): List<ForwardLogEntity>

    /** Rows made or changed at or after [since] (a retry changes a row), for the hourly and daily limits. */
    @Query("SELECT * FROM forward_log WHERE at >= :since OR updatedAt >= :since ORDER BY at DESC, id DESC")
    fun touchedSince(since: Long): List<ForwardLogEntity>

    /**
     * A row becomes SENT only while it is still PENDING: a radio failure that was reported first (it can arrive
     * before the hand-over returns) is not overwritten.
     */
    @Query("UPDATE forward_log SET status = :sent, updatedAt = :now WHERE id = :id AND status = :pending")
    fun markSentIfPending(id: Long, now: Long, sent: ForwardStatus, pending: ForwardStatus): Int

    @Query("SELECT * FROM forward_log WHERE status = :status ORDER BY at DESC, id DESC")
    fun withStatus(status: ForwardStatus): List<ForwardLogEntity>

    @Query("SELECT COUNT(*) FROM forward_log WHERE status = :status AND updatedAt > :since")
    fun observeCountSince(status: ForwardStatus, since: Long): Flow<Int>

    @Query("DELETE FROM forward_log WHERE at < :before")
    fun deleteBefore(before: Long): Int
}

class DefaultForwardLogRepository(private val dao: ForwardLogDao) : ForwardLogRepository {
    override val entries: Flow<List<ForwardLogEntry>> =
        dao.observe(ForwardLogPolicy.MAX_ROWS).map { rows -> rows.map { it.toModel() } }

    override fun failedSince(since: Long): Flow<Int> = dao.observeCountSince(ForwardStatus.FAILED, since)
}

/**
 * Writes the forward log. Every method swallows its errors: a log problem must never stop a forward. Row ids are null
 * when the row could not be written.
 */
class ForwardLogRecorder(private val dao: ForwardLogDao, private val now: () -> Long = System::currentTimeMillis) {

    fun begin(
        ruleId: Long,
        sourceAddress: String,
        sourceDate: Long,
        sourceIsMms: Boolean,
        target: String,
        subscriptionId: Int?,
        status: ForwardStatus = ForwardStatus.PENDING,
        errorCode: String? = null,
    ): Long? = runCatching {
        val t = now()
        dao.deleteBefore(t - ForwardLogPolicy.RETENTION_MS)
        dao.insert(
            ForwardLogEntity(
                at = t, updatedAt = t, ruleId = ruleId, sourceAddress = sourceAddress, sourceDate = sourceDate,
                sourceIsMms = sourceIsMms, target = target, subscriptionId = subscriptionId,
                status = status, errorCode = errorCode, attempts = if (status == ForwardStatus.SKIPPED) 0 else 1,
            )
        )
    }.getOrNull()

    /** The radio took the SMS. A row the radio already reported failed stays failed. */
    fun sent(id: Long?) {
        if (id == null) return
        runCatching { dao.markSentIfPending(id, now(), ForwardStatus.SENT, ForwardStatus.PENDING) }
    }

    fun failed(id: Long?, errorCode: String?) = finish(id, ForwardStatus.FAILED, errorCode, countAttempt = false)

    /** A retry of a failed row starts a new attempt. */
    fun retryStarted(id: Long) = finish(id, ForwardStatus.PENDING, null, countAttempt = true)

    /**
     * The radio reported that the SMS of forward [id] failed (H2): exactly that row becomes FAILED. The id travels in
     * the SMS's sent report, so a failure is never pinned on another forward to the same number.
     */
    fun radioFailed(id: Long): Boolean = runCatching {
        val row = dao.get(id) ?: return@runCatching false
        if (row.status == ForwardStatus.SKIPPED) return@runCatching false
        dao.setResult(id, ForwardStatus.FAILED, RADIO_FAILED, row.attempts, now()) > 0
    }.getOrDefault(false)

    companion object {
        const val RADIO_FAILED = "RADIO_FAILED"
    }

    private fun finish(id: Long?, status: ForwardStatus, errorCode: String?, countAttempt: Boolean) {
        if (id == null) return
        runCatching {
            val attempts = (dao.get(id)?.attempts ?: 1) + if (countAttempt) 1 else 0
            dao.setResult(id, status, errorCode, attempts, now())
        }
    }
}
