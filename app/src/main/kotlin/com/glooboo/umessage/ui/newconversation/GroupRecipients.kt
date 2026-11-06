package com.glooboo.umessage.ui.newconversation

import androidx.compose.runtime.saveable.Saver

/** One person picked for a new group conversation: the name shown on the chip and the number texted. */
data class GroupRecipient(val name: String, val number: String)

/** The same number however it was written ("+1 555-..." and "+1555..."): digits and a leading +. */
internal fun recipientKey(number: String): String = typedPhoneNumber(number) ?: number.trim()

/** Adds [recipient], or removes them when their number is already picked (a second tap on a contact). */
internal fun toggleRecipient(picked: List<GroupRecipient>, recipient: GroupRecipient): List<GroupRecipient> {
    val key = recipientKey(recipient.number)
    return if (picked.any { recipientKey(it.number) == key }) {
        picked.filterNot { recipientKey(it.number) == key }
    } else {
        picked + recipient
    }
}

/** Adds [recipient] unless their number is already picked (a typed number never removes a chip). */
internal fun addRecipient(picked: List<GroupRecipient>, recipient: GroupRecipient): List<GroupRecipient> {
    val key = recipientKey(recipient.number)
    return if (picked.any { recipientKey(it.number) == key }) picked else picked + recipient
}

internal fun isPicked(picked: List<GroupRecipient>, number: String): Boolean {
    val key = recipientKey(number)
    return picked.any { recipientKey(it.number) == key }
}

/** Keeps the picked people across rotation and the process being killed. */
internal val GroupRecipientsSaver: Saver<List<GroupRecipient>, ArrayList<String>> = Saver(
    save = { list -> list.flatMapTo(ArrayList()) { listOf(it.name, it.number) } },
    restore = { flat -> flat.chunked(2).filter { it.size == 2 }.map { GroupRecipient(it[0], it[1]) } },
)
