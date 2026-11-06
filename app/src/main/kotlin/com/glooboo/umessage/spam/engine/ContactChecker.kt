package com.glooboo.umessage.spam.engine

/** Result of a contact lookup. [UNKNOWN] means the lookup could not answer (provider error, unreadable sender). */
enum class ContactLookup { FOUND, NOT_FOUND, UNKNOWN }

interface ContactChecker {
    fun isContact(address: String): Boolean

    /** Like [isContact], but tells "not a contact" apart from "could not check". The filter uses this one. */
    fun lookup(address: String): ContactLookup = if (isContact(address)) ContactLookup.FOUND else ContactLookup.NOT_FOUND

    /** False when contacts cannot be read (permission missing), so [isContact] is meaningless. */
    fun canReadContacts(): Boolean = true

    /**
     * Whether the user has sent a message to [address] (sent, outbox, queued or failed SMS; sent or outbox MMS).
     * Such a number counts as known, like a contact. Numbers that only ever texted the user do not count.
     * [ContactLookup.UNKNOWN] when the message store could not be read.
     */
    fun sentTo(address: String): ContactLookup = ContactLookup.NOT_FOUND
}

/** Existing Fossify blocking features, kept working as part of the BLOCK stage. */
interface LegacyBlocklist {
    fun isNumberBlocked(address: String): Boolean
    fun blockedKeywords(): Collection<String>
    fun blockUnknownNumbers(): Boolean
}

object NoLegacyBlocklist : LegacyBlocklist {
    override fun isNumberBlocked(address: String) = false
    override fun blockedKeywords(): Collection<String> = emptyList()
    override fun blockUnknownNumbers() = false
}
