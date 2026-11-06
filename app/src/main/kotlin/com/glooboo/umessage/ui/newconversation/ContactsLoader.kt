package com.glooboo.umessage.ui.newconversation

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import java.text.Collator

/** One phone row from the Contacts provider, before grouping (kept separate so grouping is unit-testable). */
internal data class PhoneRow(
    val contactId: Int,
    val rawId: Int,
    val displayName: String,
    val photoUri: String,
    val number: String,
    val normalizedNumber: String,
    val type: Int,
    val label: String,
    val isPrimary: Boolean,
)

/**
 * Every contact with a phone number, straight from [Phone.CONTENT_URI] as the platform pickers do.
 *
 * The commons helper keeps only contacts with a structured given/family name or a company, so contacts saved with
 * just a display name (or just a number) never showed up and the picker said "No contacts found". Fossify's private
 * contacts provider is optional and not consulted here. Call off the main thread.
 */
internal fun loadPhoneContacts(context: Context): List<SimpleContact> {
    val projection = arrayOf(
        Phone.CONTACT_ID,
        Phone.RAW_CONTACT_ID,
        Phone.DISPLAY_NAME_PRIMARY,
        Phone.PHOTO_THUMBNAIL_URI,
        Phone.NUMBER,
        Phone.NORMALIZED_NUMBER,
        Phone.TYPE,
        Phone.LABEL,
        Phone.IS_PRIMARY,
    )
    val rows = ArrayList<PhoneRow>()
    runCatching {
        context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(4).orEmpty()
                if (number.isBlank()) continue
                rows += PhoneRow(
                    contactId = cursor.getInt(0),
                    rawId = cursor.getInt(1),
                    displayName = cursor.getString(2).orEmpty(),
                    photoUri = cursor.getString(3).orEmpty(),
                    number = number,
                    normalizedNumber = cursor.getString(5)?.takeIf { it.isNotBlank() }
                        ?: PhoneNumberUtils.normalizeNumber(number),
                    type = cursor.getInt(6),
                    label = cursor.getString(7).orEmpty(),
                    isPrimary = cursor.getInt(8) != 0,
                )
            }
        }
    }
    return groupPhoneRows(rows)
}

/**
 * One contact per aggregate contact id, numbers de-duplicated, name falling back to the first number, sorted by name.
 */
internal fun groupPhoneRows(rows: List<PhoneRow>, collator: Collator = Collator.getInstance()): List<SimpleContact> =
    rows.groupBy { it.contactId }.values.map { group ->
        val first = group.first()
        val numbers = group.distinctBy { it.normalizedNumber }.map {
            PhoneNumber(it.number, it.type, it.label, it.normalizedNumber, it.isPrimary)
        }
        SimpleContact(
            rawId = first.rawId,
            contactId = first.contactId,
            name = group.firstOrNull { it.displayName.isNotBlank() }?.displayName ?: first.number,
            photoUri = group.firstOrNull { it.photoUri.isNotBlank() }?.photoUri.orEmpty(),
            phoneNumbers = ArrayList(numbers),
            birthdays = ArrayList(),
            anniversaries = ArrayList(),
        )
    }.sortedWith(compareBy(collator) { it.name })
