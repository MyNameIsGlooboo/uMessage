package com.glooboo.umessage.spam.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.glooboo.umessage.spam.ForwardRepository
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A forward rule (added in schema 4). [targets] holds the phone numbers, one per line. */
@Entity(tableName = "forward_rules")
data class ForwardRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderType: RuleType?,
    @ColumnInfo(defaultValue = "") val senderPattern: String,
    val bodyType: RuleType?,
    @ColumnInfo(defaultValue = "") val bodyPattern: String,
    val windowStart: Int?,
    val windowEnd: Int?,
    val subscriptionId: Int?,
    val targets: String,
    @ColumnInfo(defaultValue = "SAME") val sendFrom: ForwardSim,
    @ColumnInfo(defaultValue = "0") val includeCodes: Boolean,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean,
    @ColumnInfo(defaultValue = "") val note: String,
    val createdAt: Long,
) {
    fun toModel() = ForwardRule(
        id = id,
        senderType = senderType,
        senderPattern = senderPattern,
        bodyType = bodyType,
        bodyPattern = bodyPattern,
        windowStart = windowStart,
        windowEnd = windowEnd,
        subscriptionId = subscriptionId,
        targets = targets.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
        sendFrom = sendFrom,
        includeCodes = includeCodes,
        enabled = enabled,
        note = note,
        createdAt = createdAt,
    )

    companion object {
        fun from(rule: ForwardRule) = ForwardRuleEntity(
            id = rule.id,
            senderType = rule.senderType,
            senderPattern = rule.senderPattern,
            bodyType = rule.bodyType,
            bodyPattern = rule.bodyPattern,
            windowStart = rule.windowStart,
            windowEnd = rule.windowEnd,
            subscriptionId = rule.subscriptionId,
            targets = rule.targets.joinToString("\n"),
            sendFrom = rule.sendFrom,
            includeCodes = rule.includeCodes,
            enabled = rule.enabled,
            note = rule.note,
            createdAt = rule.createdAt,
        )
    }
}

@Dao
interface ForwardDao {
    @Query("SELECT * FROM forward_rules ORDER BY createdAt DESC, id DESC")
    fun observe(): Flow<List<ForwardRuleEntity>>

    @Query("SELECT * FROM forward_rules ORDER BY createdAt ASC, id ASC")
    fun getAll(): List<ForwardRuleEntity>

    @Insert
    fun insert(rule: ForwardRuleEntity): Long

    @Update
    fun update(rule: ForwardRuleEntity): Int

    @Query("UPDATE forward_rules SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean): Int

    @Query("DELETE FROM forward_rules WHERE id = :id")
    fun delete(id: Long)
}

class DefaultForwardRepository(private val dao: ForwardDao) : ForwardRepository {
    override val rules: Flow<List<ForwardRule>> = dao.observe().map { rows -> rows.map { it.toModel() } }

    override fun currentRules(): List<ForwardRule> =
        runCatching { dao.getAll().map { it.toModel() } }.getOrDefault(emptyList())

    override suspend fun add(rule: ForwardRule): Long = withContext(Dispatchers.IO) {
        dao.insert(ForwardRuleEntity.from(rule.copy(id = 0)))
    }

    override suspend fun update(rule: ForwardRule) {
        withContext(Dispatchers.IO) {
            if (dao.update(ForwardRuleEntity.from(rule)) == 0) throw NoSuchElementException("Forward rule ${rule.id} is gone")
        }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) { dao.setEnabled(id, enabled) }
    }

    override suspend fun remove(id: Long) {
        withContext(Dispatchers.IO) { dao.delete(id) }
    }
}
