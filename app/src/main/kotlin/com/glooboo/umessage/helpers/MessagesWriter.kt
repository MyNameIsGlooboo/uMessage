package com.glooboo.umessage.helpers

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.PhoneNumberUtils
import android.util.Base64
import android.util.Log
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.models.WriteResult
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SenderAddress
import com.glooboo.umessage.spam.engine.defaultRegion
import com.glooboo.umessage.ui.settings.SimCards
import com.google.android.mms.pdu_alt.PduHeaders

/**
 * Writes restored messages into the system SMS/MMS store, one at a time, and says what happened to each.
 *
 * Writing is idempotent: a message the phone already has is recognized and skipped, so a restore can be run again
 * after it was interrupted or after some attachments failed, and it only adds what is missing.
 *
 * - An SMS is a duplicate when its conversation already has one with the same time, type and text. The conversation
 *   comes from the system's thread lookup, which treats "+1555..." and "0555..." as the same person. An SMS without a
 *   readable sender goes to the "Unknown" conversation, as the receiver and the Spam migration keep it (S17); it is
 *   a duplicate too when the store has the same time, type and text without a readable sender in any conversation.
 * - An MMS goes into the conversation the system would have put it in: the sender plus every other recipient, so a
 *   group picture message stays in its group; a received one without a readable sender goes to "Unknown". It is
 *   the same message when the store already has one, in any conversation, with the same time, box, transaction id,
 *   text and the very same addresses (each of the same type); or when its conversation has one with the same time,
 *   box, transaction id and text. The first check does not need the conversation, which depends on this phone's own
 *   number, and many phones do not tell it. A message with nobody to compare (no usable sender) is only looked for
 *   in its conversation, and when two stored messages fit, the one with the same transaction id or in the same
 *   conversation is taken, else the message is written. Its attachments and addresses are then compared one by one,
 *   with missing values compared as missing (not as the text "null"), and only the missing ones are added. An
 *   attachment that failed to write before is written again.
 *
 * Lookups are cached per conversation and the store's MMS are listed once, so a big restore asks the provider about
 * each conversation once rather than several times per message. Call [fixConversationDates] once at the end.
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

    /** An MMS of the store, as the duplicate check needs it. */
    private class StoredMms(val id: Long, val threadId: Long, val transactionId: String?)

    /**
     * Every MMS of the store by time and box, read at the first MMS. Empty when the store cannot be listed: then only
     * the conversation's own check runs, as before, and one line without message data says so.
     */
    private val storedMms: HashMap<String, MutableList<StoredMms>> by lazy {
        runCatching { listStoredMms() }.getOrElse {
            Log.w(TAG, "MMS list not read (${it.javaClass.simpleName}); duplicates are checked per conversation")
            HashMap()
        }
    }

    /** The app's number key: E.164 when the country is known, else the digits ([NumberNormalizer.comparableKey]). */
    private val numbers: NumberNormalizer by lazy { NumberNormalizer { defaultRegion(context) }.frozen() }

    fun writeSmsMessage(smsBackup: SmsBackup): WriteResult {
        val unreadable = SenderAddress.isUnreadable(smsBackup.address)
        val sms = if (unreadable) smsBackup.copy(address = SenderAddress.UNKNOWN_PLACEHOLDER) else smsBackup
        // J15-10: a text without a sender may be stored under a blank address, in any conversation.
        if (unreadable && storedWithoutSender(sms)) return WriteResult(WriteOutcome.DUPLICATE)
        val threadId = threadIdFor(setOf(sms.address))
            ?: return WriteResult(WriteOutcome.FAILED, REASON_NO_THREAD)
        val keys = smsKeys(threadId)
        val key = SmsKey(sms.date, sms.type, sms.body)
        if (key in keys) return WriteResult(WriteOutcome.DUPLICATE)

        val values = sms.toContentValues().apply { put(Sms.THREAD_ID, threadId) }
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

    /** The store has this text (time, type, text) with no readable sender (null, blank or "Unknown"), anywhere. */
    private fun storedWithoutSender(sms: SmsBackup): Boolean {
        val body = if (sms.body == null) "${Sms.BODY} IS NULL" else "${Sms.BODY} = ?"
        val selection = "${Sms.DATE} = ? AND ${Sms.TYPE} = ? AND $body AND (${Sms.ADDRESS} IS NULL OR " +
            "TRIM(${Sms.ADDRESS}, ?) = '' OR LOWER(TRIM(${Sms.ADDRESS}, ?)) = ?)"
        val args = listOfNotNull(
            sms.date.toString(), sms.type.toString(), sms.body, BLANKS, BLANKS,
            SenderAddress.UNKNOWN_PLACEHOLDER.lowercase(),
        ).toTypedArray()
        return runCatching {
            contentResolver.query(Sms.CONTENT_URI, arrayOf(Sms._ID), selection, args, null)?.use { it.moveToFirst() }
        }.getOrElse {
            Log.w(TAG, "Blank-sender SMS not looked for (${it.javaClass.simpleName}); checked per conversation")
            null
        } == true
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
        val known = mmsRecipients(backup)
        val recipients = known.ifEmpty { unknownSender(backup) }
        if (recipients.isEmpty()) return WriteResult(WriteOutcome.FAILED, REASON_NO_ADDRESS)
        // J15-15: a message without a sender is looked for store-wide too, and its other addresses must agree.
        val noSender = known.isEmpty()
        // Already in the store, in whatever conversation: it gets no second copy and no new conversation.
        val stored = findStoredMms(backup, recipients, noSender)
        val threadId = stored?.threadId ?: threadIdFor(recipients)
            ?: return WriteResult(WriteOutcome.FAILED, REASON_NO_THREAD)

        var created = false
        val wantedAddresses = if (noSender) {
            usableAddresses(backup.addresses.map { AddressKey(it.type, it.address) })
        } else {
            null
        }
        val messageId = stored?.id ?: findMms(backup, threadId, wantedAddresses) ?: run {
            val values = backup.toContentValues().apply { put(Mms.THREAD_ID, threadId) }
            val uri = contentResolver.insert(Mms.CONTENT_URI, values)
                ?: return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
            created = true
            modifiedThreadIds += threadId
            val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: findMms(backup, threadId)
                ?: return WriteResult(WriteOutcome.FAILED, REASON_REFUSED)
            storedMms.getOrPut(mmsKey(backup.date, backup.messageBox)) { mutableListOf() } +=
                StoredMms(id, threadId, backup.transactionId)
            id
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
            // The same address in another form (case, +44 or 0) is the stored one: no second row for it.
            val wantedAddress = address.address.trim()
            if (existingAddresses.any { it.type == address.type && sameAddress(it.address.trim(), wantedAddress) }) {
                continue
            }
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
        fun usable(address: String) = usableAddress(address)
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

    /**
     * S17: a received MMS without a readable sender, sent to this phone alone, goes to the "Unknown" chat as such an
     * SMS does. One without any To address is not a message the phone received and stays refused.
     */
    private fun unknownSender(backup: MmsBackup): Set<String> {
        val received = backup.messageBox == Mms.MESSAGE_BOX_INBOX
        val noSender = backup.addresses.none { it.type == PduHeaders.FROM && usableAddress(it.address) }
        // Any To row counts, also a blank one: such a message has no better chat than "Unknown" either way.
        val toThisPhone = backup.addresses.any { it.type == PduHeaders.TO }
        return if (received && noSender && toThisPhone) setOf(SenderAddress.UNKNOWN_PLACEHOLDER) else emptySet()
    }

    private fun threadIdFor(recipients: Set<String>): Long? = threadIds[recipients] ?: try {
        Threads.getOrCreateThreadId(context, recipients).takeIf { it > 0 }?.also { threadIds[recipients] = it }
    } catch (_: Exception) {
        null
    }

    private fun usableAddress(address: String) =
        address.isNotBlank() && !address.trim().equals(MMS_NO_ADDRESS, ignoreCase = true)

    private fun mmsKey(date: Long, box: Int) = "$date:$box"

    private fun listStoredMms(): HashMap<String, MutableList<StoredMms>> {
        val byKey = HashMap<String, MutableList<StoredMms>>()
        contentResolver.query(
            Mms.CONTENT_URI, arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX, Mms.TRANSACTION_ID),
            null, null, null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(Mms._ID)
            val thread = c.getColumnIndexOrThrow(Mms.THREAD_ID)
            val date = c.getColumnIndexOrThrow(Mms.DATE)
            val box = c.getColumnIndexOrThrow(Mms.MESSAGE_BOX)
            val trId = c.getColumnIndexOrThrow(Mms.TRANSACTION_ID)
            while (c.moveToNext()) {
                byKey.getOrPut(mmsKey(c.getLong(date), c.getInt(box))) { mutableListOf() } +=
                    StoredMms(c.getLong(id), c.getLong(thread), if (c.isNull(trId)) null else c.getString(trId))
            }
        }
        return byKey
    }

    /**
     * The same MMS anywhere in the store: same time, box, transaction id (when both have one) and text, and the same
     * addresses of the same types both ways. The conversation is not compared at first: which one the message is in
     * depends on whether this phone knows its own number, and a second copy in a new conversation must never be made.
     * A message with nobody to compare is left to its conversation's check. When more than one fits, the one with the
     * same transaction id or in this conversation is taken; if that does not decide, none is, so the message is
     * written rather than merged into another. A message without a sender ([noSender]) is looked for the same way
     * (the stored one has no usable sender either, as the address check shows), but only when it has the same
     * transaction id as the stored one or at least one usable address of its own to compare.
     */
    private fun findStoredMms(backup: MmsBackup, recipients: Set<String>, noSender: Boolean = false): StoredMms? {
        val wanted = usableAddresses(backup.addresses.map { AddressKey(it.type, it.address) })
        val comparable = keyPeople(backup.messageBox, wanted).isNotEmpty() || (noSender && wanted.isNotEmpty()) ||
            (noSender && backup.transactionId != null)
        val candidates = if (!comparable) {
            emptyList()
        } else {
            storedMms[mmsKey(backup.date, backup.messageBox)].orEmpty().filter {
                it.transactionId == null || backup.transactionId == null || it.transactionId == backup.transactionId
            }.filter {
                // Nothing to compare but the transaction id: it must be the same one, not just a missing one.
                !noSender || wanted.isNotEmpty() || it.transactionId == backup.transactionId
            }
        }
        if (candidates.isEmpty()) return null
        val wantedText = textOf(backup.parts.map { it.contentType to (it.text ?: it.data) })
        val matches = candidates.filter { stored ->
            textOf(existingParts(stored.id).map { it.contentType to it.text }) == wantedText &&
                sameAddresses(wanted, usableAddresses(existingAddresses(stored.id)))
        }
        return pickStoredMms(matches, backup, recipients)
    }

    /** One match, or the one with the same transaction id, or the one in this conversation; else none. */
    private fun pickStoredMms(matches: List<StoredMms>, backup: MmsBackup, recipients: Set<String>): StoredMms? {
        if (matches.size <= 1) return matches.firstOrNull()
        val sameTransaction = backup.transactionId?.let { trId -> matches.singleOrNull { it.transactionId == trId } }
        return sameTransaction
            ?: threadIdFor(recipients)?.let { thread -> matches.singleOrNull { it.threadId == thread } }
    }

    private fun usableAddresses(addresses: Collection<AddressKey>): List<AddressKey> =
        addresses.filter { usableAddress(it.address) }.map { AddressKey(it.type, it.address.trim()) }

    /** Who tells a message apart: the sender of a received MMS, the recipients of a sent one. */
    private fun keyPeople(box: Int, addresses: List<AddressKey>): List<AddressKey> {
        val types = if (box == Mms.MESSAGE_BOX_INBOX) {
            setOf(PduHeaders.FROM)
        } else {
            setOf(PduHeaders.TO, PduHeaders.CC, PduHeaders.BCC)
        }
        return addresses.filter { it.type in types }
    }

    /** Every address of each side is on the other side with the same type. */
    private fun sameAddresses(wanted: List<AddressKey>, stored: List<AddressKey>): Boolean {
        fun covers(a: List<AddressKey>, b: List<AddressKey>) =
            a.all { x -> b.any { y -> x.type == y.type && sameAddress(x.address, y.address) } }
        return covers(wanted, stored) && covers(stored, wanted)
    }

    /**
     * One address, strictly: a name without digits only as the same text (case aside), a number only by the app's
     * number key, never by the last 7 digits.
     */
    private fun sameAddress(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        if (a.none(Char::isDigit) || b.none(Char::isDigit)) return false
        val key = runCatching { numbers.comparableKey(a) }.getOrDefault("")
        return key.isNotEmpty() && key == runCatching { numbers.comparableKey(b) }.getOrDefault("")
    }

    /** [addresses]: when given, a candidate must have the same usable addresses both ways (no sender). */
    private fun findMms(backup: MmsBackup, threadId: Long, addresses: List<AddressKey>? = null): Long? {
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
        }.filter { (id, _) ->
            addresses == null || sameAddresses(addresses, usableAddresses(existingAddresses(id)))
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
        private const val TAG = "uMessageRestore"
        private const val CACHED_THREADS = 32
        private const val MMS_NO_ADDRESS = "insert-address-token"

        /** What TRIM removes from an address before it counts as blank. */
        private const val BLANKS = " \t\r\n"

        // Reasons are stable keys; the summary screen turns them into text.
        const val REASON_NO_ADDRESS = "no_address"
        const val REASON_NO_THREAD = "no_thread"
        const val REASON_REFUSED = "refused"
        const val REASON_NOT_IN_BACKUP = "attachment_not_in_backup"
        const val REASON_DAMAGED_ATTACHMENT = "attachment_damaged"
        const val REASON_ATTACHMENT_WRITE = "attachment_write_failed"
    }
}
