package com.glooboo.umessage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The unsent text of the chat with [addresses] and when it was typed (epoch millis). */
@Serializable
data class DraftBackup(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("body") val body: String,
    @SerialName("date") val date: Long,
)

/** A muted chat; [until] is when the mute ends (epoch millis), [Long.MAX_VALUE] for a mute that never ends. */
@Serializable
data class MuteBackup(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("until") val until: Long,
)

/**
 * A message of the recycle bin. The message itself is in the file as a normal SMS or MMS; this names it by who it is
 * from, its time in seconds and a fingerprint of its text (see StarredMessages.fingerprint), as a star does.
 */
@Serializable
data class RecycledBackup(
    @SerialName("address") val address: String,
    @SerialName("date") val date: Long,
    @SerialName("body_hash") val bodyHash: Int,
    @SerialName("mms") val isMms: Boolean = false,
)

/**
 * A text message waiting to be sent at [at] (epoch millis). [repeat] is a repeat rule name, or null for none, and
 * [calendar], [anchorDay] and [anchorMonth] say how it counts. Messages with attachments are not backed up.
 */
@Serializable
data class ScheduledBackup(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("body") val body: String,
    @SerialName("at") val at: Long,
    @SerialName("repeat") val repeat: String? = null,
    @SerialName("calendar") val calendar: String? = null,
    @SerialName("anchor_day") val anchorDay: Int = 0,
    @SerialName("anchor_month") val anchorMonth: Int = 0,
)

/** A reminder about a message of the chat with [addresses]; [body] is what its notification shows. */
@Serializable
data class ReminderBackup(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("remind_at") val remindAt: Long,
    @SerialName("body") val body: String,
    @SerialName("mms") val isMms: Boolean = false,
)

@Serializable
data class SavedTextBackup(
    @SerialName("title") val title: String,
    @SerialName("text") val text: String,
)
