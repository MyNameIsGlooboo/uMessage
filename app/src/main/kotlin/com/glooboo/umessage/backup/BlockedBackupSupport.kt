package com.glooboo.umessage.backup

import android.content.Context
import android.util.Base64
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.BlockedLimits
import com.glooboo.umessage.spam.data.BlockedMessageEntity
import com.glooboo.umessage.spam.data.MigrationMark
import com.glooboo.umessage.spam.data.SpamDao
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SenderAddress
import com.glooboo.umessage.spam.mms.BlockedMmsStore

/**
 * The Blocked folder in a backup, one [BlockedBackup] entry per message. Reading streams: the rows are loaded without
 * their pictures and each kept MMS is read, encoded and written on its own, so one is in memory at a time.
 *
 * Restoring writes only to the Blocked folder's own table. It sends nothing, shows no notification, counts no
 * "blocked" event for the weekly digest, and never touches the inbox or the system store. A message the folder
 * already has (same sender, text, kind and time) is left alone, so restoring twice adds nothing.
 *
 * Provider truth (section 9, S16): the phone's store holds Spam, so a backup reads it per chat like any other
 * message; only the held rows that are not moved yet go along as entries of their own. A restored entry is held here
 * as today and the message migration then writes it to the store with its probe, so restoring twice writes nothing
 * twice ([com.glooboo.umessage.helpers.MessagesImporter]).
 */
class BlockedBackupSupport(
    private val dao: SpamDao,
    private val readMedia: (name: String) -> ByteArray?,
    private val saveMedia: (pdu: ByteArray) -> String?,
) {

    /** Calls [emit] for each blocked message of the kinds wanted; returns how many it emitted. */
    fun collect(sms: Boolean, mms: Boolean, emit: (BlockedBackup) -> Unit): Int {
        var count = 0
        for (row in dao.getAllBlocked().sortedBy { it.receivedAt }) {
            // Moved (or nothing to move): the store's copy is in the backup as a message.
            val moved = MigrationMark.isDone(row.migratedMsgId)
            if (!(if (row.isMms) mms else sms) || moved) continue
            val data = row.mediaFile?.takeIf { row.media == MmsMedia.STORED }?.let(readMedia)
                ?.takeIf { it.size <= MAX_MEDIA_BYTES }
            emit(
                BlockedBackup(
                    address = row.address,
                    body = row.body,
                    isMms = row.isMms,
                    receivedAt = row.receivedAt,
                    reason = row.reason.name,
                    media = (if (data != null) MmsMedia.STORED else withoutMedia(row)).name,
                    mediaData = data?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                )
            )
            count++
        }
        return count
    }

    /** What a message without bytes in the file is: an MMS whose content is not kept, or an SMS. */
    private fun withoutMedia(row: BlockedMessageEntity) =
        if (row.isMms) MmsMedia.MISSING else MmsMedia.NONE

    /**
     * Puts [entry] in the Blocked folder; false when it was already there or is not usable. An entry without a
     * readable sender is kept under the "Unknown" placeholder, the address the message migration writes such a row to
     * (S17), so it reaches the store's "Unknown" chat once like any other; a held row of the same message under
     * "Unknown" (any case), a blank address or the address as written in the file counts as already there.
     */
    fun restore(entry: BlockedBackup): Boolean {
        if (entry.receivedAt <= 0) return false
        val given = BlockedLimits.limitAddress(entry.address)
        val unreadable = SenderAddress.isUnreadable(entry.address)
        val address = if (unreadable) SenderAddress.UNKNOWN_PLACEHOLDER else given
        val body = BlockedLimits.limitBody(
            if (entry.isMms && entry.body.isBlank()) MMS_PLACEHOLDER_BODY else entry.body,
        )
        val held = (if (unreadable) UNREADABLE_FORMS + given else setOf(address)).any {
            dao.findDuplicateBlocked(it, body, entry.isMms, entry.receivedAt, entry.receivedAt) != null
        }
        if (held) return false
        val pdu = entry.mediaData?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        val file = if (entry.isMms && pdu != null) saveMedia(pdu) else null
        val media = when {
            !entry.isMms -> MmsMedia.NONE
            file != null -> MmsMedia.STORED
            else -> MmsMedia.MISSING
        }
        dao.insertBlocked(
            BlockedMessageEntity(
                address = address,
                body = body,
                subscriptionId = -1,
                isMms = entry.isMms,
                receivedAt = entry.receivedAt,
                reason = DecisionReason.entries.firstOrNull { it.name == entry.reason } ?: DecisionReason.BLOCK_RULE,
                ruleId = null,
                media = media,
                mediaFile = file,
            )
        )
        return true
    }

    companion object {
        /** A kept MMS larger than this is left out of the file; the message itself still goes along. */
        const val MAX_MEDIA_BYTES = 8 * 1024 * 1024

        /** The forms a held row without a readable sender is found under ([SenderAddress.isUnreadable]). */
        private val UNREADABLE_FORMS = setOf(
            SenderAddress.UNKNOWN_PLACEHOLDER, SenderAddress.UNKNOWN_PLACEHOLDER.lowercase(),
            SenderAddress.UNKNOWN_PLACEHOLDER.uppercase(), "", " ", "\t",
        )

        fun of(context: Context): BlockedBackupSupport {
            val app = context.applicationContext
            return BlockedBackupSupport(
                dao = SpamDatabase.getInstance(app).spamDao(),
                readMedia = { name -> runCatching { BlockedMmsStore.pduFile(app, name)?.readBytes() }.getOrNull() },
                saveMedia = { pdu -> BlockedMmsStore.save(app, pdu) },
            )
        }
    }
}
