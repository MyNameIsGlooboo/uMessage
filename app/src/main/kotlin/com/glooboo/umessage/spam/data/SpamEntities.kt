package com.glooboo.umessage.spam.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule

@Entity(tableName = "spam_rules", indices = [Index(value = ["kind", "type", "pattern", "subscriptionId"], unique = true)])
data class SpamRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ListKind,
    val type: RuleType,
    val pattern: String,
    val subscriptionId: Int?,
    val note: String,
    val createdAt: Long,
) {
    fun toRule() = SpamRule(id, kind, type, pattern, subscriptionId, note, createdAt)

    companion object {
        fun from(rule: SpamRule) = SpamRuleEntity(
            rule.id, rule.kind, rule.type, rule.pattern, rule.subscriptionId, rule.note, rule.createdAt
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
) {
    fun toModel() =
        BlockedMessage(id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId, media, mediaFile, unread)

    companion object {
        fun from(message: BlockedMessage) = BlockedMessageEntity(
            message.id, message.address, message.body, message.subscriptionId, message.isMms,
            message.receivedAt, message.reason, message.ruleId, message.media, message.mediaFile, message.unread
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
