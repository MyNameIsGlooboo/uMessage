package com.glooboo.umessage.ui.thread

import android.provider.Telephony.Sms

/** What one person of a group text has got so far. */
enum class RecipientState { Pending, Sent, Delivered, NotDelivered, NotSent }

/** One person of a group text: the address the text went to and the name to show for it. */
data class GroupPerson(val address: String, val name: String)

/**
 * One person's own copy of a group text as the SMS provider holds it (a group text goes out as one text per person,
 * each in that person's chat, plus a summary row in the group's chat). [dateSentMillis] is when the delivery report
 * arrived, 0 while there is none.
 */
data class CopyRow(val type: Int, val status: Int, val dateMillis: Long, val dateSentMillis: Long = 0L)

/** One line of the delivery details. [timeMillis] is when the state was reached, null when it is not known. */
data class RecipientDelivery(val person: GroupPerson, val state: RecipientState, val timeMillis: Long?)

/**
 * The delivery details of a group message. [perPerson] is false when the carrier only gives one state for everybody
 * (a group picture message), so every line repeats the overall one.
 */
data class DeliveryDetails(val recipients: List<RecipientDelivery>, val perPerson: Boolean)

/**
 * The state of one copy. A failed or waiting copy says so; a delivery report of failure (MS2) is "not delivered";
 * "delivered" only while delivery reports are on, because a network can report delivery the app never asked for.
 */
fun copyState(copy: CopyRow, deliveryReportsOn: Boolean): RecipientState = when {
    copy.type == Sms.MESSAGE_TYPE_FAILED -> RecipientState.NotSent
    copy.type == Sms.MESSAGE_TYPE_OUTBOX || copy.type == Sms.MESSAGE_TYPE_QUEUED -> RecipientState.Pending
    isDeliveryFailed(copy.status) -> RecipientState.NotDelivered
    deliveryReportsOn && copy.status == Sms.STATUS_COMPLETE -> RecipientState.Delivered
    else -> RecipientState.Sent
}

/** When [copy] reached [state]: the report's time for a report, else the time it was sent; null while waiting. */
fun copyTime(copy: CopyRow, state: RecipientState): Long? {
    val millis = when (state) {
        RecipientState.Pending -> return null
        RecipientState.Delivered, RecipientState.NotDelivered ->
            copy.dateSentMillis.takeIf { it > 0 } ?: copy.dateMillis
        RecipientState.Sent, RecipientState.NotSent -> copy.dateMillis
    }
    return millis.takeIf { it > 0 }
}

/**
 * Each person's state for a group message whose summary row is [summary]. An SMS group has one [copies] row per
 * address; a person without one (the copy was deleted, or never saved) shows the summary row's state. A picture
 * message has no per-person state at all (no delivery report is read for it): everybody shows the overall state.
 */
fun groupDeliveryDetails(
    people: List<GroupPerson>,
    summary: CopyRow,
    isMms: Boolean,
    copies: Map<String, CopyRow>,
    deliveryReportsOn: Boolean,
): DeliveryDetails {
    // The summary row's status column never holds a delivery (a picture message's is empty and reads as "complete").
    val overall = summary.copy(status = Sms.STATUS_NONE)
    val overallState = copyState(overall, deliveryReportsOn)
    val lines = people.distinctBy { it.address }.map { person ->
        val copy = if (isMms) null else copies[person.address]
        if (copy == null) {
            RecipientDelivery(person, overallState, copyTime(overall, overallState))
        } else {
            val state = copyState(copy, deliveryReportsOn)
            RecipientDelivery(person, state, copyTime(copy, state))
        }
    }
    return DeliveryDetails(lines, perPerson = !isMms)
}
