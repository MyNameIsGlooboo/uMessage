package com.glooboo.umessage.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import com.glooboo.umessage.extensions.draftsDB
import com.glooboo.umessage.extensions.getPhoneNumberFromAddressId
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.spam.engine.ContactNumberIndex
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.defaultRegion

/**
 * Lookups that building the conversation list needs for every thread: recipient addresses, contact names and
 * photos, and drafts.
 *
 * In [bulk] mode each source is read once, on first use, and then matched in memory. The full list used to run
 * three or four provider queries per thread, which took about 10 s with a few thousand threads. Without [bulk]
 * (a single thread, e.g. on an incoming message) the old per-item queries are cheaper than reading everything.
 */
class ConversationLookups(private val context: Context, private val bulk: Boolean) {
    private val simpleContactsHelper by lazy { SimpleContactsHelper(context) }

    private val addresses: Map<Int, String>? by lazy { if (bulk) readCanonicalAddresses() else null }
    private val contacts: ContactNumberIndex<ContactEntry>? by lazy { if (bulk) readContacts() else null }
    private val drafts: Map<Long, Draft>? by lazy {
        if (bulk) runCatching { context.draftsDB.getAll().associateBy { it.threadId } }.getOrNull() else null
    }

    fun phoneNumber(canonicalAddressId: Int): String {
        val all = addresses ?: return context.getPhoneNumberFromAddressId(canonicalAddressId)
        return all[canonicalAddressId] ?: context.getPhoneNumberFromAddressId(canonicalAddressId)
    }

    /** The contact's name, or [number] itself when no contact has it (same contract as SimpleContactsHelper). */
    fun name(number: String): String {
        val all = contacts ?: return simpleContactsHelper.getNameFromPhoneNumber(number)
        return all.find(number)?.name ?: number
    }

    fun photoUri(number: String): String {
        val all = contacts ?: return simpleContactsHelper.getPhotoUriFromPhoneNumber(number)
        return all.find(number)?.photoUri ?: ""
    }

    fun draft(threadId: Long): Draft? {
        val all = drafts ?: return context.draftsDB.getDraftById(threadId)
        return all[threadId]
    }

    private fun readCanonicalAddresses(): Map<Int, String>? = try {
        val result = HashMap<Int, String>()
        val uri = Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses")
        context.contentResolver.query(uri, arrayOf(Mms._ID, Mms.Addr.ADDRESS), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getIntValue(Mms._ID)] = cursor.getStringValue(Mms.Addr.ADDRESS) ?: ""
            }
        }
        result
    } catch (_: Exception) {
        null
    }

    /**
     * Every phone number in the address book. Numbers are matched in full when both have a country, so a stranger
     * whose number ends in the same 9 digits as a contact's is not shown as that contact (see ContactNumberIndex).
     * Null when contacts can't be read, so callers fall back to per-number lookups.
     */
    private fun readContacts(): ContactNumberIndex<ContactEntry>? {
        val region = defaultRegion(context)
        val normalizer = NumberNormalizer { region }
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return ContactNumberIndex(normalizer)
        return try {
            val result = ContactNumberIndex<ContactEntry>(normalizer)
            val projection = arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.DISPLAY_NAME, Phone.PHOTO_URI)
            context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getStringValue(Phone.DISPLAY_NAME)
                    if (name.isNullOrEmpty()) continue
                    val entry = ContactEntry(name, cursor.getStringValue(Phone.PHOTO_URI) ?: "")
                    listOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER).forEach { column ->
                        val number = cursor.getStringValue(column)
                        if (!number.isNullOrEmpty()) {
                            // Keep the first match, but prefer one with a photo, as a lookup would show it.
                            result.add(number, entry) { existing -> existing.photoUri.isEmpty() && entry.photoUri.isNotEmpty() }
                        }
                    }
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private data class ContactEntry(val name: String, val photoUri: String)
}
