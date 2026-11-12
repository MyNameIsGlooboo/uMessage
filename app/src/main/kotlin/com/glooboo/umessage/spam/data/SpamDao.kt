package com.glooboo.umessage.spam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType

@Dao
interface SpamDao {
    @Insert
    fun insertPending(message: PendingSmsEntity): Long

    @Query("SELECT * FROM pending_sms ORDER BY createdAt ASC, id ASC")
    fun getPending(): List<PendingSmsEntity>

    /** Returns how many rows went (0 when the replay already delivered it). */
    @Query("DELETE FROM pending_sms WHERE id = :id")
    fun deletePending(id: Long): Int

    @Query("SELECT * FROM spam_rules ORDER BY createdAt DESC, id DESC")
    fun observeRules(): Flow<List<SpamRuleEntity>>

    @Query("SELECT * FROM spam_rules")
    fun getRules(): List<SpamRuleEntity>

    @Query("SELECT * FROM spam_rules WHERE kind = :kind AND type = :type AND pattern = :pattern AND subscriptionId IS :subscriptionId LIMIT 1")
    fun findRule(kind: ListKind, type: RuleType, pattern: String, subscriptionId: Int?): SpamRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRule(rule: SpamRuleEntity): Long

    @Query("DELETE FROM spam_rules WHERE id = :id")
    fun deleteRule(id: Long)

    @Query("DELETE FROM spam_rules WHERE id IN (:ids)")
    fun deleteRules(ids: List<Long>)

    @Query("SELECT * FROM spam_rules WHERE id = :id")
    fun getRule(id: Long): SpamRuleEntity?

    @Update
    fun updateRule(rule: SpamRuleEntity): Int

    /** Counts one more message blocked by rule [id] (no row changes when the rule was deleted meanwhile). */
    @Query("UPDATE spam_rules SET hitCount = hitCount + 1, lastHit = :at WHERE id = :id")
    fun recordRuleHit(id: Long, at: Long): Int

    @Query("SELECT * FROM blocked_messages ORDER BY receivedAt DESC, id DESC")
    fun observeBlocked(): Flow<List<BlockedMessageEntity>>

    @Query("SELECT * FROM blocked_messages")
    fun getAllBlocked(): List<BlockedMessageEntity>

    @Query("SELECT * FROM blocked_messages WHERE receivedAt BETWEEN :from AND :to")
    fun getBlockedBetween(from: Long, to: Long): List<BlockedMessageEntity>

    @Query("SELECT * FROM blocked_messages WHERE id = :id")
    fun getBlocked(id: Long): BlockedMessageEntity?

    @Query("SELECT id FROM blocked_messages WHERE address = :address AND body = :body AND isMms = :isMms AND receivedAt BETWEEN :from AND :to LIMIT 1")
    fun findDuplicateBlocked(address: String, body: String, isMms: Boolean, from: Long, to: Long): Long?

    @Insert
    fun insertBlocked(message: BlockedMessageEntity): Long

    @Query("DELETE FROM blocked_messages WHERE id IN (:ids)")
    fun deleteBlocked(ids: List<Long>)

    @Query("DELETE FROM blocked_messages WHERE receivedAt < :before")
    fun deleteBlockedBefore(before: Long): Int

    @Query("UPDATE blocked_messages SET media = :media, mediaFile = :mediaFile, body = :body WHERE id = :id")
    fun updateMedia(id: Long, media: MmsMedia, mediaFile: String?, body: String): Int

    /** Opening a sender's thread reads its messages: the Blocked tab row stops being bold. */
    @Query("UPDATE blocked_messages SET unread = 0 WHERE id IN (:ids) AND unread = 1")
    fun markBlockedRead(ids: List<Long>): Int

    @Query("SELECT mediaFile FROM blocked_messages WHERE id IN (:ids) AND mediaFile IS NOT NULL")
    fun mediaFilesOf(ids: List<Long>): List<String>

    @Query("SELECT mediaFile FROM blocked_messages WHERE receivedAt < :before AND mediaFile IS NOT NULL")
    fun mediaFilesBefore(before: Long): List<String>

    @Query("SELECT mediaFile FROM blocked_messages WHERE mediaFile IS NOT NULL")
    fun allMediaFiles(): List<String>

    @Insert
    fun insertEvent(event: SpamEventEntity): Long

    /** Undo of a restore takes its "restored" event back, so the counter does not count it. */
    @Query("DELETE FROM spam_events WHERE id = :id")
    fun deleteEvent(id: Long)

    @Query("SELECT COUNT(*) FROM spam_events WHERE type = :type")
    fun observeEventCount(type: SpamEventType): Flow<Int>

    @Query("SELECT COUNT(*) FROM spam_events WHERE type = :type AND timestamp >= :since")
    fun observeEventCountSince(type: SpamEventType, since: Long): Flow<Int>
}
