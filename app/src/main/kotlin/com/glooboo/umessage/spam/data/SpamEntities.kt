package com.glooboo.umessage.spam.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule

/**
 * Since schema 9 (provider truth, 7.2) a spam rule covers both SIMs: [subscriptionId] is always null and the unique
 * index is on kind, type and pattern only. The column stays so older backups and a downgrade still read.
 */
@Entity(tableName = "spam_rules", indices = [Index(value = ["kind", "type", "pattern"], unique = true)])
data class SpamRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ListKind,
    val type: RuleType,
    val pattern: String,
    val subscriptionId: Int?,
    val note: String,
    val createdAt: Long,
    /** Added in version 8: how many messages this rule blocked. Rules from before it start at 0. */
    @ColumnInfo(defaultValue = "0") val hitCount: Int = 0,
    /** Added in version 8: when this rule last blocked a message in milliseconds, 0 = never. */
    @ColumnInfo(defaultValue = "0") val lastHit: Long = 0L,
    /** Added in version 9 (U15): "Keep" was tapped on the chat line of this automatic allow rule. */
    @ColumnInfo(defaultValue = "0") val acknowledged: Boolean = false,
) {
    fun toRule() = SpamRule(id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, lastHit, acknowledged)

    companion object {
        fun from(rule: SpamRule) = SpamRuleEntity(
            rule.id, rule.kind, rule.type, rule.pattern, rule.subscriptionId, rule.note, rule.createdAt,
            rule.hitCount, rule.lastHit, rule.acknowledged,
        )
    }
}

@Entity(tableName = "blocked_messages", indices = [Index("receivedAt")])
data class BlockedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val subscriptionId: Int,
    val isMms: Boolean,
    val receivedAt: Long,
    val reason: DecisionReason,
    val ruleId: Long?,
    /** Added in version 3. Rows from version 2 get NONE for an SMS and MISSING for an MMS (nothing was kept). */
    @ColumnInfo(defaultValue = "NONE") val media: MmsMedia = MmsMedia.NONE,
    /** Name of the kept MMS PDU under BlockedMmsStore's app-private directory. Added in version 3. */
    val mediaFile: String? = null,
    /** Added in version 6. Rows from before it count as read, so an update does not make every old chat bold. */
    @ColumnInfo(defaultValue = "0") val unread: Boolean = false,
    /**
     * Added in version 9 (S2, S18): the id of the row this message became in the phone's message store. A "done" mark
     * of the message migration, also used by its verify step; never used to find the message later. Null = not moved.
     */
    val migratedMsgId: Long? = null,
    /** Added in version 9: failed verifies of the message migration (at most 2 per row). */
    @ColumnInfo(defaultValue = "0") val migrateTries: Int = 0,
) {
    fun toModel() = BlockedMessage(
        id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId, media, mediaFile, unread, migratedMsgId,
        migrateTries,
    )

    companion object {
        /** A-10: the migration marks travel with the model, so an Undo that re-inserts a row keeps them. */
        fun from(message: BlockedMessage) = BlockedMessageEntity(
            message.id, message.address, message.body, message.subscriptionId, message.isMms,
            message.receivedAt, message.reason, message.ruleId, message.media, message.mediaFile, message.unread,
            message.migratedMsgId, message.migrateTries,
        )
    }
}

enum class SpamEventType { BLOCKED, RESTORED }

@Entity(tableName = "spam_events", indices = [Index("type", "timestamp")])
data class SpamEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SpamEventType,
    val timestamp: Long,
)

/**
 * D3 A: one member of a group rule (schema 9). The primary key is the group and the sender's key
 * ([AllowGroupMember.senderKey]), so a sender is in a group at most once. Stored, never derived: deleting the code
 * text or the sent text later does not move the chat.
 */
@Entity(tableName = "allow_group_members", primaryKeys = ["group_type", "sender_key"])
data class AllowGroupMemberEntity(
    @ColumnInfo(name = "group_type") val group: AllowGroup,
    @ColumnInfo(name = "sender_key") val senderKey: String,
    val address: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val source: MemberSource,
    val acknowledged: Boolean,
) {
    fun toModel() = AllowGroupMember(group, senderKey, address, addedAt, source, acknowledged)

    companion object {
        fun from(member: AllowGroupMember) = AllowGroupMemberEntity(
            member.group, member.senderKey, member.address, member.addedAt, member.source, member.acknowledged,
        )
    }
}
