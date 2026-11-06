package com.glooboo.umessage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The private notes of a messages backup, one extra entry at the end of the JSON array (`"backupType":
 * "umessage_notes"`). Entries are matched to messages by [NoteBackup.key] (see MessageNoteKey), never by id, so the
 * notes find their messages again after a restore. An older build reads the entry as one damaged entry and goes on.
 */
@Serializable
data class NotesBackup(
    @SerialName("notes")
    val notes: List<NoteBackup>,

    override val backupType: BackupType = BackupType.NOTES,
) : MessagesBackup()

@Serializable
data class NoteBackup(
    @SerialName("key") val key: String,
    @SerialName("date") val date: Int,
    @SerialName("note") val note: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) {
    fun toNote() = MessageNote(key, 0L, date, note, createdAt, updatedAt)

    companion object {
        fun of(note: MessageNote) = NoteBackup(note.key, note.messageDate, note.text, note.createdAt, note.updatedAt)
    }
}
