package com.glooboo.umessage.backup

import android.content.Context
import android.util.Base64
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.BlockedLimits
import com.glooboo.umessage.spam.data.BlockedMessageEntity
import com.glooboo.umessage.spam.data.SpamDao
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.mms.BlockedMmsStore

/**
 * The Blocked folder in a backup, one [BlockedBackup] entry per message. Reading streams: the rows are loaded without
 * their pictures and each kept MMS is read, encoded and written on its own, so one is in memory at a time.
 *
 * Restoring writes only to the Blocked folder's own table. It sends nothing, shows no notification, counts no
 * "blocked" event for the weekly digest, and never touches the inbox or the system store. A message the folder
 * already has (same sender, text, kind and time) is left alone, so restoring twice adds nothing.
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
            if (!(if (row.isMms) mms else sms)) continue
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

    /** Puts [entry] in the Blocked folder; false when it was already there or is not usable. */
    fun restore(entry: BlockedBackup): Boolean {
        if (entry.address.isBlank() || entry.receivedAt <= 0) return false
        val address = BlockedLimits.limitAddress(entry.address)
        val body = BlockedLimits.limitBody(
            if (entry.isMms && entry.body.isBlank()) MMS_PLACEHOLDER_BODY else entry.body,
        )
        if (dao.findDuplicateBlocked(address, body, entry.isMms, entry.receivedAt, entry.receivedAt) != null) {
            return false
        }
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
