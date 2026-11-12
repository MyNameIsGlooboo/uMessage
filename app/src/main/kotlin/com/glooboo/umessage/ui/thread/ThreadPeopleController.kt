package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.text.input.clearText
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.ui.components.pickPhoneNumber
import com.google.gson.Gson
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleContact

/**
 * The "people" panel of the thread menu: shows who the conversation is with, adds a contact or a typed number,
 * removes someone, and on "Done" opens the conversation with the new set of people (one or several: a group).
 *
 * [recipients] and [setRecipients] read and replace the screen's people; [privateContacts] are contacts kept only
 * in Fossify Contacts, offered as suggestions too; [onPeopleChanged] runs after every change.
 */
class ThreadPeopleController(
    private val activity: Activity,
    private val vm: ThreadViewModel,
    private val recipients: () -> ThreadRecipients,
    private val setRecipients: (ThreadRecipients) -> Unit,
    private val privateContacts: () -> List<SimpleContact>,
    private val threadId: () -> Long,
    private val onPeopleChanged: () -> Unit,
) {
    /** Opens the panel, or closes it when it is open. */
    fun toggle() {
        if (vm.showPeople) {
            activity.hideKeyboard()
            vm.showPeople = false
        } else {
            showChips()
            vm.showPeople = true
            loadSuggestions()
        }
    }

    private fun loadSuggestions() {
        SimpleContactsHelper(activity).getAvailableContacts(false) { contacts ->
            contacts.addAll(privateContacts())
            activity.runOnUiThread { vm.contacts = contacts }
        }
    }

    private fun showChips() {
        val people = recipients().contacts
        val firstId = people.firstOrNull()?.rawId
        vm.people = people.map { PersonChip(it.rawId, it.name, removable = it.rawId != firstId) }
    }

    /** A suggested contact: with several numbers the user picks one. */
    fun pickContact(contact: SimpleContact) {
        activity.pickPhoneNumber(contact.phoneNumbers) { phoneNumber ->
            add(contact.copy(phoneNumbers = arrayListOf(phoneNumber)))
        }
    }

    /** Adds the typed number; text that is not a phone number (letters) is left in the field and not added. */
    fun addNumber(number: String) {
        val valid = ThreadRecipients.typedNumberOrNull(number) ?: return
        add(ThreadRecipients.contactForNumber(valid))
    }

    private fun add(contact: SimpleContact) {
        vm.peopleQuery.clearText()
        val current = recipients()
        val next = current + contact // not the same person again, by id or by phone number (B15)
        if (next.size == current.size) return
        changeTo(next)
    }

    fun remove(rawId: Int) = changeTo(recipients() - rawId)

    private fun changeTo(people: ThreadRecipients) {
        setRecipients(people)
        vm.isGroup = people.isGroup
        showChips()
        onPeopleChanged()
    }

    /** "Done": a different set of people is a different conversation, which opens in place of this one. */
    fun confirm() {
        activity.hideKeyboard()
        vm.showPeople = false
        val numbers = HashSet(recipients().addresses)
        val newThreadId = activity.getThreadId(numbers)
        if (threadId() != newThreadId) {
            activity.hideKeyboard()
            Intent(activity, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, newThreadId)
                putExtra(THREAD_NUMBER, Gson().toJson(numbers))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activity.startActivity(this)
            }
        }
    }
}
