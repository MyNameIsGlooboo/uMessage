package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.engine.ContactLookup

/**
 * I28, I04: an unknown sender is one who is not in the contacts and whom the user never wrote to. A group has no single
 * sender. A lookup that could not answer ([ContactLookup.UNKNOWN]) never makes a sender unknown: better to miss one
 * than to call a contact a stranger.
 */
fun isUnknownSender(inContacts: ContactLookup, writtenTo: ContactLookup, isGroup: Boolean = false): Boolean =
    !isGroup && inContacts == ContactLookup.NOT_FOUND && writtenTo == ContactLookup.NOT_FOUND
