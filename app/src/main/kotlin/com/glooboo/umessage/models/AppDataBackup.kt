package com.glooboo.umessage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the app keeps beside the messages, as one extra entry at the end of the JSON array (`"backupType":
 * "umessage_appdata"`): starred messages, labels with the chats under them, archived, muted and hidden chats, drafts,
 * messages in the recycle bin, scheduled messages, reminders and saved texts. Chats are named by the numbers of their
 * people, never by thread id, because a restore hands out other ids; messages are named by their time, sender and a
 * fingerprint of their text. Every list defaults to empty and unknown fields are ignored, so files of other versions
 * still read. An older build reads the entry as one damaged entry and goes on, as it does for notes.
 */
@Serializable
data class AppDataBackup(
    @SerialName("stars") val stars: List<StarBackup> = emptyList(),
    @SerialName("labels") val labels: List<LabelBackup> = emptyList(),
    @SerialName("label_links") val labelLinks: List<LabelLinkBackup> = emptyList(),
    @SerialName("archived") val archived: List<ChatRefBackup> = emptyList(),
    @SerialName("drafts") val drafts: List<DraftBackup> = emptyList(),
    @SerialName("muted") val muted: List<MuteBackup> = emptyList(),
    @SerialName("hidden") val hidden: List<ChatRefBackup> = emptyList(),
    @SerialName("recycled") val recycled: List<RecycledBackup> = emptyList(),
    @SerialName("scheduled") val scheduled: List<ScheduledBackup> = emptyList(),
    @SerialName("reminders") val reminders: List<ReminderBackup> = emptyList(),
    @SerialName("saved_texts") val savedTexts: List<SavedTextBackup> = emptyList(),

    override val backupType: BackupType = BackupType.APP_DATA,
) : MessagesBackup() {
    val size
        get() = stars.size + labels.size + labelLinks.size + archived.size + drafts.size + muted.size +
            hidden.size + recycled.size + scheduled.size + reminders.size + savedTexts.size
    val isEmpty get() = size == 0
}

@Serializable
data class StarBackup(
    @SerialName("address") val address: String,
    @SerialName("date") val date: Long,
    @SerialName("body_hash") val bodyHash: Int,
    @SerialName("starred_ts") val starredTs: Long,
    @SerialName("body") val body: String,
    @SerialName("mms") val isMms: Boolean = false,
)

@Serializable
data class LabelBackup(
    @SerialName("name") val name: String,
    @SerialName("color") val color: Int,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** A chat ([addresses]) under the label called [label]. */
@Serializable
data class LabelLinkBackup(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("label") val label: String,
)

@Serializable
data class ChatRefBackup(
    @SerialName("addresses") val addresses: List<String>,
)
