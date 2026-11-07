package com.glooboo.umessage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One message of the Blocked folder, one entry of the JSON array (`"backupType": "umessage_blocked"`). It is its own
 * entry type, never an SMS entry with a flag: an older build must not read it as an inbox message, and reads it as a
 * damaged entry instead. Restoring puts it back in the Blocked folder only, never in the inbox or the system store.
 *
 * [media] is a `MmsMedia` name; the kept MMS ([mediaData], Base64) goes along when it is small enough. The rule that
 * blocked it is not kept (its id means nothing on another phone), only the [reason].
 */
@Serializable
data class BlockedBackup(
    @SerialName("address") val address: String,
    @SerialName("body") val body: String,
    @SerialName("mms") val isMms: Boolean = false,
    @SerialName("received_at") val receivedAt: Long,
    @SerialName("reason") val reason: String,
    @SerialName("media") val media: String = "NONE",
    @SerialName("media_data") val mediaData: String? = null,

    override val backupType: BackupType = BackupType.BLOCKED,
) : MessagesBackup()
