package com.glooboo.umessage.models

import android.provider.Telephony
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.helpers.THREAD_RECEIVED_MESSAGE
import com.glooboo.umessage.helpers.THREAD_SENT_MESSAGE
import com.glooboo.umessage.helpers.generateStableId

/**
 * A message in the app's own database. The primary key is ([id], [isMMS]): see [MessageKey] for why the provider id
 * alone is not unique and how to look a row up.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["id", "is_mms"],
    indices = [Index(value = ["thread_id", "date"])]
)
data class Message(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "type") val type: Int,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "participants") val participants: ArrayList<SimpleContact>,
    @ColumnInfo(name = "date") val date: Int,
    @ColumnInfo(name = "read") val read: Boolean,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "is_mms") val isMMS: Boolean,
    @ColumnInfo(name = "attachment") val attachment: MessageAttachment?,
    @ColumnInfo(name = "sender_phone_number") val senderPhoneNumber: String,
    @ColumnInfo(name = "sender_name") var senderName: String,
    @ColumnInfo(name = "sender_photo_uri") val senderPhotoUri: String,
    @ColumnInfo(name = "subscription_id") var subscriptionId: Int,
    @ColumnInfo(name = "is_scheduled") var isScheduled: Boolean = false
) : ThreadItem() {

    /** This row's identity in the messages table and the recycle bin. */
    val key: MessageKey
        get() = MessageKey(id, isMMS)

    fun isReceivedMessage() = type == Telephony.Sms.MESSAGE_TYPE_INBOX

    fun millis() = date * 1000L

    /**
     * A scheduled message whose send failed (RM-25). It is held with its past time until the user retries or
     * deletes it, so it is never treated as an expired schedule.
     */
    fun isHeldScheduled() = isScheduled && type == Telephony.Sms.MESSAGE_TYPE_FAILED

    /** A scheduled message whose time passed without being sent or held: left over, and safe to drop. */
    fun isExpiredScheduled(nowMillis: Long) = isScheduled && !isHeldScheduled() && millis() < nowMillis

    fun getSender(): SimpleContact? =
        participants.firstOrNull { it.doesHavePhoneNumber(senderPhoneNumber) }
            ?: participants.firstOrNull { it.name == senderName }
            ?: participants.firstOrNull()

    fun getStableId(): Long {
        val providerBit = if (isMMS) 1L else 0L
        val key = (id shl 1) or providerBit
        val type = if (isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
        return generateStableId(type, key)
    }

    fun getSelectionKey(): Int {
        val key = (id shl 1) or (if (isMMS) 1L else 0L)
        return (key xor (key ushr Int.SIZE_BITS)).toInt()
    }

    companion object {
        fun areItemsTheSame(old: Message, new: Message): Boolean {
            return old.key == new.key
        }

        fun areContentsTheSame(old: Message, new: Message): Boolean {
            return old.body == new.body &&
                old.threadId == new.threadId &&
                old.date == new.date &&
                old.isMMS == new.isMMS &&
                old.attachment == new.attachment &&
                old.senderPhoneNumber == new.senderPhoneNumber &&
                old.senderName == new.senderName &&
                old.senderPhotoUri == new.senderPhotoUri &&
                old.isScheduled == new.isScheduled
        }
    }
}
