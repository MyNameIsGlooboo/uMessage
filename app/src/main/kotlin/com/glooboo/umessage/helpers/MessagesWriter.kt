package com.glooboo.umessage.helpers

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.PhoneNumberUtils
import android.util.Base64
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.models.WriteResult
import com.glooboo.umessage.ui.settings.SimCards
import com.google.android.mms.pdu_alt.PduHeaders

/**
 * Writes restored messages into the system SMS/MMS store, one at a time, and says what happened to each.
 *
 * Writing is idempotent: a message the phone already has is recognized and skipped, so a restore can be run again
 * after it was interrupted or after some attachments failed, and it only adds what is missing.
 *
 * - An SMS is a duplicate when its conversation already has one with the same time, type and text. The conversation
 *   comes from the system's thread lookup, which treats "+1555..." and "0555..." as the same person.
 * - An MMS goes into the conversation the system would have put it in: the sender plus every other recipient, so a
 *   group picture message stays in its group. It is the same message when the conversation already has one with the
 *   same time, box, transaction id and text; its attachments and addresses are then compared one by one, with
 *   missing values compared as missing (not as the text "null"), and only the missing ones are added. An attachment
 *   that failed to write before is written again.
 *
 * Lookups are cached per conversation, so a big restore asks the provider about each conversation once rather than
 * several times per message. Call [fixConversationDates] once at the end.
 *
 * @param ownNumbers this phone's own numbers, left out of group MMS recipients the way the MMS library does when a
 *   message arrives. Empty when the phone does not tell us its numbers.
 */
class MessagesWriter(
    private val context: Context,
    ownNumbers: (() -> List<String>)? = null,
) {
    private val contentResolver = context.contentResolver
    private val modifiedThreadIds = mutableSetOf<Long>()
    private val threadIds = HashMap<Set<String>, Long>()
    private val smsKeysByThread = object : LinkedHashMap<Long, MutableSet<SmsKey>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableSet<SmsKey>>?) = size > CACHED_THREADS
    }
    private val ownNumbers: List<String> by lazy {
        ownNumbers?.invoke() ?: runCatching { SimCards.load(context).mapNotNull { it.number } }.getOrDefault(emptyList())
    }

    private data class SmsKey(val date: Long, val type: Int, val body: String?)

    fun writeSmsMessage(smsBackup: SmsBackup): WriteResult {
        if (smsBackup.address.isBlank()) return WriteResult(WriteOutcome.FAILED, REASON_NO_ADDRESS)
        val threadId = threadIdFor(setOf(smsBackup.address))
            ?: return WriteResult(WriteOutcome.FAILED, REASON_NO_THREAD)
        val keys = smsKeys(threadId)
        val key = SmsKey(smsBackup.date, smsBackup.type, smsBackup.body)
        if (key in keys) return WriteResult(WriteOutcome.DUPLICATE)

        val values = smsBackup.toContentValues().apply { put(Sms.THREAD_ID, threadId) }
        val uri = try {
            contentResolver.insert(Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            return WriteResult(WriteOutcome.FAILED, reasonOf(e))
        } ?: return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
        if (uri.lastPathSegment == "0") return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
        keys += key
        modifiedThreadIds += threadId
        return WriteResult(WriteOutcome.ADDED)
    }

    private fun smsKeys(threadId: Long): MutableSet<SmsKey> = smsKeysByThread.getOrPut(threadId) {
        val keys = HashSet<SmsKey>()
        contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms.DATE, Sms.TYPE, Sms.BODY), "${Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                keys += SmsKey(cursor.getLong(0), cursor.getInt(1), if (cursor.isNull(2)) null else cursor.getString(2))
            }
        }
        keys
    }

    fun writeMmsMessage(mmsBackup: MmsBackup): WriteResult = try {
        writeMms(mmsBackup)
    } catch (e: Exception) {
        WriteResult(WriteOutcome.FAILED, reasonOf(e))
    }

    private fun writeMms(backup: MmsBackup): WriteResult {
        val recipients = mmsRecipients(backup)
        if (recipients.isEmpty()) return WriteResult(WriteOutcome.FAILED, REASON_NO_ADDRESS)
        val threadId = threadIdFor(recipients) ?: return WriteResult(WriteOutcome.FAILED, REASON_NO_THREAD)

        var created = false
        val messageId = findMms(backup, threadId) ?: run {
            val values = backup.toContentValues().apply { put(Mms.THREAD_ID, threadId) }
            val uri = contentResolver.insert(Mms.CONTENT_URI, values)
                ?: return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
            created = true
            modifiedThreadIds += threadId
            uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: findMms(backup, threadId)
                ?: return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
        }

        var wrote = 0
        var missing = 0
        var failed = 0
        var lastFailure: String? = null
        val existingParts = existingParts(messageId).toMutableList()
        for (part in backup.parts) {
            val match = existingParts.firstOrNull { samePart(it, part) }
            if (match != null) {
                existingParts.remove(match)
                if (part.isNonText() && !partHasData(match.id)) {
                    // An earlier restore created the attachment but its bytes never arrived: write them again.
                    when (val error = writePartData(partUri(match.id), part)) {
                        null -> wrote++
                        REASON_NOT_IN_BACKUP -> missing++
                        else -> { failed++; lastFailure = error }
                    }
                }
                continue
            }
            if (part.isNonText() && part.data.isNullOrEmpty()) {
                // Never add an empty attachment: it would show as a blank picture forever.
                missing++
                continue
            }
            val values = part.toContentValues().apply {
                put(Mms.Part.MSG_ID, messageId)
                if (part.text == null && !part.isNonText() && part.contentType.startsWith("text/", true)) {
                    put(Mms.Part.TEXT, part.data)
                }
            }
            val partUri = contentResolver.insert(Uri.parse("content://mms/$messageId/part"), values)
            if (partUri == null) {
                failed++; lastFailure = REASON_REFUSED
                continue
            }
            val error = if (part.isNonText()) writePartData(partUri, part) else null
            if (error == null) {
                wrote++
            } else {
                // Remove the empty attachment so the message does not show a blank picture, and so the next restore
                // sees it as missing and tries again.
                runCatching { contentResolver.delete(partUri, null, null) }
                failed++; lastFailure = error
            }
        }

        val existingAddresses = existingAddresses(messageId)
        for (address in backup.addresses) {
            if (AddressKey(address.type, address.address) in existingAddresses) continue
            val values = address.toContentValues().apply { put(Mms.Addr.MSG_ID, messageId) }
            if (contentResolver.insert(addrUri(messageId), values) != null) {
                existingAddresses += AddressKey(address.type, address.address)
                wrote++
            }
        }

        if (wrote > 0) modifiedThreadIds += threadId
        return when {
            failed > 0 -> WriteResult(WriteOutcome.INCOMPLETE, lastFailure)
            missing > 0 -> WriteResult(WriteOutcome.INCOMPLETE, REASON_NOT_IN_BACKUP)
            created -> WriteResult(WriteOutcome.ADDED)
            wrote > 0 -> WriteResult(WriteOutcome.REPAIRED)
            else -> WriteResult(WriteOutcome.DUPLICATE)
        }
    }

    /**
     * The conversation an MMS belongs to, built the way the MMS library does when it stores a message: a received
     * message goes to the sender plus the other To/Cc recipients (a lone To address is this phone, and this phone's
     * own numbers are left out); a sent message goes to its To, Cc and Bcc recipients.
     */
    internal fun mmsRecipients(backup: MmsBackup): Set<String> {
        fun usable(address: String) = address.isNotBlank() && !address.equals(MMS_NO_ADDRESS, ignoreCase = true)
        fun isOwn(address: String) = ownNumbers.any { PhoneNumberUtils.compare(it, address) }
        val byType = backup.addresses.groupBy({ it.type }, { it.address.trim() })
        val recipients = linkedSetOf<String>()
        if (backup.messageBox == Mms.MESSAGE_BOX_INBOX) {
            byType[PduHeaders.FROM].orEmpty().filter(::usable).forEach { recipients += it }
            for (type in intArrayOf(PduHeaders.TO, PduHeaders.CC)) {
                val list = byType[type].orEmpty()
                if (list.size > 1) list.filter { usable(it) && !isOwn(it) }.forEach { recipients += it }
            }
        } else {
            for (type in intArrayOf(PduHeaders.TO, PduHeaders.CC, PduHeaders.BCC)) {
                byType[type].orEmpty().filter { usable(it) && !isOwn(it) }.forEach { recipients += it }
            }
        }
        return recipients
    }

    private fun threadIdFor(recipients: Set<String>): Long? = threadIds[recipients] ?: try {
        Threads.getOrCreateThreadId(context, recipients).takeIf { it > 0 }?.also { threadIds[recipients] = it }
    } catch (_: Exception) {
        null
    }

    private fun findMms(backup: MmsBackup, threadId: Long): Long? {
        val candidates = mutableListOf<Pair<Long, String?>>()
        contentResolver.query(
            Mms.CONTENT_URI, arrayOf(Mms._ID, Mms.TRANSACTION_ID),
            "${Mms.THREAD_ID} = ? AND ${Mms.DATE} = ? AND ${Mms.MESSAGE_BOX} = ?",
            arrayOf(threadId.toString(), backup.date.toString(), backup.messageBox.toString()), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                candidates += cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }
        val sameTransaction = candidates.filter { (_, trId) ->
            trId == null || backup.transactionId == null || trId == backup.transactionId
        }
        if (sameTransaction.isEmpty()) return null
        val wantedText = textOf(backup.parts.map { it.contentType to (it.text ?: it.data) })
        val withParts = sameTransaction.map { (id, _) -> id to existingParts(id) }
        return withParts.firstOrNull { (_, parts) -> textOf(parts.map { it.contentType to it.text }) == wantedText }?.first
            // A message an earlier restore created but could not fill.
            ?: withParts.firstOrNull { (_, parts) -> parts.isEmpty() }?.first
    }

    private fun textOf(parts: List<Pair<String, String?>>) =
        parts.filter { it.first.equals("text/plain", true) }.mapNotNull { it.second }.joinToString("\n")

    private class ExistingPart(
        val id: Long, val contentType: String, val seq: Int, val name: String?, val contentLocation: String?,
        val contentId: String?, val text: String?,
    )

    private fun existingParts(messageId: Long): List<ExistingPart> {
        val parts = mutableListOf<ExistingPart>()
        contentResolver.query(
            Uri.parse("content://mms/$messageId/part"),
            arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.SEQ, Mms.Part.NAME, Mms.Part.CONTENT_LOCATION,
                Mms.Part.CONTENT_ID, Mms.Part.TEXT),
            "${Mms.Part.MSG_ID} = ?", arrayOf(messageId.toString()), null,
        )?.use { c ->
            fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
            while (c.moveToNext()) {
                parts += ExistingPart(c.getLong(0), str(1).orEmpty(), c.getInt(2), str(3), str(4), str(5), str(6))
            }
        }
        return parts
    }

    private fun samePart(existing: ExistingPart, part: MmsPart): Boolean {
        if (!existing.contentType.equals(part.contentType, ignoreCase = true)) return false
        if (existing.seq != part.sequenceOrder) return false
        if (existing.name != part.name || existing.contentLocation != part.contentLocation) return false
        if (existing.contentId != part.contentId) return false
        return part.isNonText() || existing.text == (part.text ?: part.data)
    }

    private fun partHasData(partId: Long): Boolean = try {
        contentResolver.openInputStream(partUri(partId))?.use { it.read() != -1 } == true
    } catch (_: Exception) {
        false
    }

    /** Writes an attachment's bytes; returns null on success or the reason it failed. */
    private fun writePartData(uri: Uri, part: MmsPart): String? {
        val data = part.data
        if (data.isNullOrEmpty()) return REASON_NOT_IN_BACKUP
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return REASON_DAMAGED_ATTACHMENT
        }
        if (bytes.isEmpty()) return REASON_NOT_IN_BACKUP
        return try {
            val stream = contentResolver.openOutputStream(uri, "w") ?: return REASON_ATTACHMENT_WRITE
            stream.use { it.write(bytes) }
            null
        } catch (_: Exception) {
            REASON_ATTACHMENT_WRITE
        }
    }

    private data class AddressKey(val type: Int, val address: String)

    private fun existingAddresses(messageId: Long): MutableSet<AddressKey> {
        val addresses = HashSet<AddressKey>()
        contentResolver.query(
            addrUri(messageId), arrayOf(Mms.Addr.TYPE, Mms.Addr.ADDRESS), "${Mms.Addr.MSG_ID} = ?",
            arrayOf(messageId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) addresses += AddressKey(c.getInt(0), if (c.isNull(1)) "" else c.getString(1))
        }
        return addresses
    }

    private fun partUri(partId: Long) = Uri.parse("content://mms/part/$partId")
    private fun addrUri(messageId: Long) = Uri.parse("content://mms/$messageId/addr")

    private fun reasonOf(e: Exception) = e.message?.take(120)?.let { "${e.javaClass.simpleName}: $it" }
        ?: e.javaClass.simpleName

    /** Fixes the timestamps of all conversations modified by previous writes. */
    fun fixConversationDates() {
        // When we insert a message, Android's Telephony provider sets the conversation date to the current time
        // rather than the time of the message. Source:
        // https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/android14-release/src/com/android/providers/telephony/MmsSmsDatabaseHelper.java#134
        if (modifiedThreadIds.isNotEmpty()) context.updateLastConversationMessage(modifiedThreadIds)
    }

    companion object {
        private const val CACHED_THREADS = 32
        private const val MMS_NO_ADDRESS = "insert-address-token"

        // Reasons are stable keys; the summary screen turns them into text.
        const val REASON_NO_ADDRESS = "no_address"
        const val REASON_NO_THREAD = "no_thread"
        const val REASON_REFUSED = "refused"
        const val REASON_NOT_IN_BACKUP = "attachment_not_in_backup"
        const val REASON_DAMAGED_ATTACHMENT = "attachment_damaged"
        const val REASON_ATTACHMENT_WRITE = "attachment_write_failed"
    }
}
