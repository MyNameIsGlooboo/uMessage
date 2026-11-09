package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.UnknownSenderMode

class DefaultSpamFilter(
    private val rules: () -> List<SpamRule>,
    private val settings: () -> SpamSettings,
    private val contacts: ContactChecker,
    private val normalizer: NumberNormalizer,
    private val legacy: LegacyBlocklist = NoLegacyBlocklist,
    /** Country of the SIM that received a message, used to read national-format senders. Null = default region. */
    private val regionForSubscription: (Int) -> String? = { null },
) : SpamFilter {

    /** Number, sender id, keyword and pattern matching, shared with the forward rules. */
    private val matcher = RuleMatcher(normalizer, regionForSubscription)

    override fun evaluate(message: IncomingMessage): FilterDecision {
        val numbers = matcher.numberReaders(message.subscriptionId)
        val scoped = rules().filter { it.subscriptionId == null || it.subscriptionId == message.subscriptionId }

        scoped.firstOrNull { it.kind == ListKind.ALLOW && matches(it, message, numbers) }?.let {
            return FilterDecision.Allow(DecisionReason.ALLOW_RULE, it.id)
        }

        scoped.firstOrNull { it.kind == ListKind.BLOCK && matches(it, message, numbers) }?.let {
            return FilterDecision.Block(DecisionReason.BLOCK_RULE, it.id)
        }
        if (matchesLegacy(message)) return FilterDecision.Block(DecisionReason.BLOCK_RULE)

        val isPhoneNumber = !normalizer.isSenderId(message.address)
        val lookup = if (isPhoneNumber) contactLookup(message.address) else ContactLookup.NOT_FOUND
        if (lookup == ContactLookup.FOUND) return FilterDecision.Allow(DecisionReason.CONTACT)
        // Q2: the foreign-number hold belongs to Smart and Strict only; with the filter Off nothing is held.
        val mode = effectiveMode()
        if (mode != UnknownSenderMode.OFF) foreignDecision(message, isPhoneNumber, lookup)?.let { return it }

        // Without contacts access, or when the lookup itself failed, every number looks unknown. Blocking them all
        // would silently hide messages from family and friends, so unknown-sender blocking is skipped for phone
        // numbers (rules above still apply). Alphanumeric sender ids are never contacts, so they are filtered as
        // usual. A message whose sender could not be read (blank address, e.g. an MMS whose header did not parse)
        // cannot be told apart from a contact either, so it is treated the same way.
        val contactsKnown = !isPhoneNumber || (lookup == ContactLookup.NOT_FOUND && canReadContacts())

        if (mode == UnknownSenderMode.OFF) return FilterDecision.Allow(DecisionReason.MODE_OFF)

        // A number the user has written to is known, like a contact. Looked up only now, when it decides something.
        if (isPhoneNumber && message.address.isNotBlank()) {
            when (sentLookup(message.address)) {
                ContactLookup.FOUND -> return FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION)
                // The history could not be read: it might be someone the user talks to, so do not block.
                ContactLookup.UNKNOWN -> return FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE)
                ContactLookup.NOT_FOUND -> Unit
            }
        }

        if (!contactsKnown) return FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE)

        return when (mode) {
            UnknownSenderMode.SMART -> {
                // Owner decision (Q5): a verification code is let through even when the message has a link.
                if (isExemptCode(message.body)) {
                    FilterDecision.Allow(DecisionReason.OTP_EXEMPTION)
                } else {
                    FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)
                }
            }

            else -> FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)
        }
    }

    /**
     * The old Fossify "block unknown numbers" switch turns blocking on when the mode is Off, and otherwise leaves the
     * chosen mode in charge, so it cannot quietly take Smart mode's code exemption away. Strict has no exemptions,
     * Smart lets codes through (the old "codes from unknown senders" setting is gone).
     */
    private fun effectiveMode(): UnknownSenderMode {
        val legacyUnknown = runCatching { legacy.blockUnknownNumbers() }.getOrDefault(false)
        val mode = settings().mode
        return if (mode == UnknownSenderMode.OFF && legacyUnknown) UnknownSenderMode.STRICT else mode
    }

    private fun isExemptCode(body: String): Boolean = OtpDetector.looksLikeOtp(body)

    /**
     * I24: a number from outside the chosen home country is held, unless the user knows it. Contacts and numbers the
     * user wrote to are never held, and when that cannot be checked the message is let in, as with unknown senders.
     * Smart mode keeps letting codes through.
     */
    private fun foreignDecision(
        message: IncomingMessage,
        isPhoneNumber: Boolean,
        lookup: ContactLookup,
    ): FilterDecision? {
        val home = settings().blockOutsideRegion
        if (home.isBlank() || !isPhoneNumber || !RegionBlock.isForeign(message.address, home)) return null
        if (lookup != ContactLookup.NOT_FOUND || !canReadContacts()) {
            return FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE)
        }
        return when (sentLookup(message.address)) {
            ContactLookup.FOUND -> FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION)
            ContactLookup.UNKNOWN -> FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE)
            ContactLookup.NOT_FOUND ->
                if (settings().mode == UnknownSenderMode.SMART && isExemptCode(message.body)) {
                    FilterDecision.Allow(DecisionReason.OTP_EXEMPTION)
                } else {
                    FilterDecision.Block(DecisionReason.FOREIGN_NUMBER)
                }
        }
    }

    /** A failure reading the message history is UNKNOWN, never NOT_FOUND, so it cannot block someone the user knows. */
    private fun sentLookup(address: String): ContactLookup =
        runCatching { contacts.sentTo(address) }.getOrDefault(ContactLookup.UNKNOWN)

    /** Blank addresses and lookup failures are UNKNOWN, never NOT_FOUND, so they cannot block a contact. */
    private fun contactLookup(address: String): ContactLookup {
        if (address.isBlank()) return ContactLookup.UNKNOWN
        return runCatching { contacts.lookup(address) }.getOrDefault(ContactLookup.UNKNOWN)
    }

    private fun canReadContacts(): Boolean = runCatching { contacts.canReadContacts() }.getOrDefault(false)

    /**
     * The old Fossify keyword and number lists. A failure in either (a provider error, a malformed entry) skips only
     * that list: the rest of the filter still runs, so one bad entry cannot switch filtering off for every message.
     */
    private fun matchesLegacy(message: IncomingMessage): Boolean {
        val keywords = runCatching { legacy.blockedKeywords() }.getOrDefault(emptyList())
        // Older installs could store whitespace-only keywords; one of those would match every message.
        if (keywords.any { it.isNotBlank() && TextNormalizer.containsObfuscated(message.body, it) }) return true
        return message.address.isNotBlank() && runCatching { legacy.isNumberBlocked(message.address) }.getOrDefault(false)
    }

    private fun matches(rule: SpamRule, message: IncomingMessage, numbers: List<NumberNormalizer>): Boolean =
        matcher.matches(rule, message, numbers)
}
