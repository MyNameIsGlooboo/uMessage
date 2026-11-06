package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.helpers.isUnknownSender
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.SpamEngine
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS

/** I04: works out whether the "Not in your contacts" card shows for the open conversation and tells [vm]. */
internal class UnknownCardController(private val context: Context, private val vm: ThreadViewModel) {

    /** Whether the only person is a stranger. Blocking (contact and message lookups): call off the main thread. */
    fun isStranger(number: String?, isSaved: Boolean, isGroup: Boolean): Boolean {
        if (number == null) return false
        val inContacts = when {
            isSaved -> ContactLookup.FOUND
            context.hasPermission(PERMISSION_READ_CONTACTS) -> ContactLookup.NOT_FOUND
            else -> ContactLookup.UNKNOWN
        }
        val writtenTo = when {
            inContacts != ContactLookup.NOT_FOUND -> ContactLookup.UNKNOWN
            SpamEngine.hasWrittenTo(context, number) -> ContactLookup.FOUND
            else -> ContactLookup.NOT_FOUND
        }
        return isUnknownSender(inContacts, writtenTo, isGroup)
    }

    fun refresh(stranger: Boolean, recipients: ThreadRecipients, newest: Message?, threadId: Long, hasRule: Boolean) {
        val prefs = context.threadPrefs
        vm.unknownCard.onDismissed = { prefs.dismissUnknownCard(threadId) }
        val show = UnknownCardLogic.shouldShow(
            isUnknown = stranger && recipients.size == 1,
            hasReceived = newest != null,
            isRecycleBin = vm.isRecycleBin,
            isSenderName = recipients.hasShortCodeWithLetters,
            hasRule = hasRule,
            dismissed = prefs.isUnknownCardDismissed(threadId),
        )
        vm.unknownCard.update(show, show && UnknownCardLogic.needsCaution(newest?.body))
    }
}
