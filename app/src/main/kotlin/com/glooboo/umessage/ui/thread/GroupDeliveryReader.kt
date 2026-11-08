package com.glooboo.umessage.ui.thread

import android.content.Context
import android.provider.Telephony.Sms
import com.glooboo.umessage.models.Message

/** The copies of a group text are all written in the same send, so they are within this of the summary row. */
private const val COPY_WINDOW_MS = 60_000L

// Column positions of the projection in [readCopies]: type, status, date, date sent.
private const val COL_TYPE = 0
private const val COL_STATUS = 1
private const val COL_DATE = 2
private const val COL_DATE_SENT = 3

/** The people a group message went to: one per different address, with the name the message holds for them. */
internal fun groupPeopleOf(message: Message): List<GroupPerson> =
    message.participants.mapNotNull { contact ->
        val phone = contact.phoneNumbers.firstOrNull()
        val number = phone?.normalizedNumber?.ifBlank { phone.value }.orEmpty()
        if (number.isBlank()) null else GroupPerson(number, contact.name.ifBlank { number })
    }.distinctBy { it.address }

/**
 * Each person's own copy of the group SMS [message] from the SMS provider, by address: the newest sent, waiting or
 * failed row to that address with the same text within a minute of the summary row. People without one are missing.
 * Blocking: call it off the main thread. Nothing is stored; the provider already holds every state.
 */
internal fun readGroupCopies(context: Context, people: List<GroupPerson>, message: Message): Map<String, CopyRow> {
    val groupMillis = message.millis()
    val copies = HashMap<String, CopyRow>()
    for (person in people) {
        runCatching {
            context.contentResolver.query(
                Sms.CONTENT_URI,
                arrayOf(Sms.TYPE, Sms.STATUS, Sms.DATE, Sms.DATE_SENT),
                "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ? AND ${Sms.TYPE} != ? AND ${Sms.DATE} BETWEEN ? AND ?",
                arrayOf(
                    person.address, message.body, Sms.MESSAGE_TYPE_INBOX.toString(),
                    (groupMillis - COPY_WINDOW_MS).toString(), (groupMillis + COPY_WINDOW_MS).toString(),
                ),
                "${Sms.DATE} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    copies[person.address] =
                        CopyRow(
                            cursor.getInt(COL_TYPE), cursor.getInt(COL_STATUS),
                            cursor.getLong(COL_DATE), cursor.getLong(COL_DATE_SENT),
                        )
                }
            }
        }
    }
    return copies
}

/** The delivery details of the group message [message]: per person for an SMS group, overall for a picture message. */
internal fun loadDeliveryDetails(context: Context, message: Message, deliveryReportsOn: Boolean): DeliveryDetails {
    val people = groupPeopleOf(message)
    val copies = if (message.isMMS) emptyMap() else readGroupCopies(context, people, message)
    val summary = CopyRow(message.type, message.status, message.millis())
    return groupDeliveryDetails(people, summary, message.isMMS, copies, deliveryReportsOn)
}
