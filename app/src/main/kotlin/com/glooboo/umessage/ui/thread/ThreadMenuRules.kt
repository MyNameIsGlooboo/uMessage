package com.glooboo.umessage.ui.thread

/** What decides the top bar menu; see [buildThreadMenu]. */
data class ThreadMenuInput(
    val recipients: ThreadRecipients,
    val hasItems: Boolean,
    val hasConversation: Boolean,
    /** Null while the conversation is not loaded. */
    val isArchived: Boolean?,
    val isRecycleBin: Boolean,
    val archiveAvailable: Boolean,
    val singleNumberIsSaved: Boolean,
    /** The single sender's address when a block rule already covers it, else null. */
    val reportedSender: String?,
    /** The single sender's address when any uMessage block rule names it, else null. */
    val blockedSender: String?,
    /** The single sender's address when a uMessage allow rule names it, else null. */
    val allowedSender: String? = null,
    /** I48: the launcher accepts pinned shortcuts (ShortcutManagerCompat.isRequestPinShortcutSupported). */
    val pinShortcutSupported: Boolean = false,
    /** False in the Google Play build, which has no forwarding: no "Always forward" entry. */
    val forwardingAvailable: Boolean = true,
    /** N05: the conversation is muted right now. */
    val muted: Boolean = false,
    /** B06: the conversation was hidden behind the app lock. */
    val hiddenChat: Boolean = false,
)

private const val SHORT_CODE_MIN_DIGITS = 5
private const val SHORT_CODE_MAX_DIGITS = 6

/**
 * Digits only, five or six of them: a service short code such as "300012", which takes no calls (TH-30). Shorter
 * numbers stay callable: 110 or 115 are emergency and help lines.
 */
fun isNumericShortCode(address: String): Boolean {
    val trimmed = address.trim()
    return trimmed.length in SHORT_CODE_MIN_DIGITS..SHORT_CODE_MAX_DIGITS && trimmed.all { it in '0'..'9' }
}

/** Which overflow menu entries apply to the conversation. */
fun buildThreadMenu(input: ThreadMenuInput): ThreadMenuState = with(input) {
    val address = recipients.reportableAddress
    val single = recipients.size == 1
    val firstNumber = recipients.contacts.firstOrNull()?.phoneNumbers?.firstOrNull()?.value
    val special = recipients.hasShortCodeWithLetters
    ThreadMenuState(
        delete = hasItems,
        restore = hasItems && isRecycleBin,
        archive = hasItems && isArchived == false && !isRecycleBin && archiveAvailable,
        unarchive = hasItems && isArchived == true && !isRecycleBin && archiveAvailable,
        rename = recipients.isGroup && hasConversation && !isRecycleBin,
        details = hasConversation && !isRecycleBin,
        // One sender only: in a group "Block sender" would not say whom it blocks, and would block contacts too. A
        // single sender that a block rule already fully covers is offered "Unblock" instead.
        block = single && !isRecycleBin && !(address != null && address == reportedSender),
        unblock = single && address != null && address == blockedSender && !isRecycleBin,
        // TH-30: neither a sender name nor a short code ("300012") answers a call.
        dial = single && !special && !recipients.hasShortCode && !isRecycleBin,
        managePeople = !special && !isRecycleBin,
        markUnread = hasItems && !isRecycleBin,
        // Saving the number is offered only while it is not a contact yet. N3: a sender name ("AX-AMAZN") is not a
        // number, so it is neither saved as one nor copied as one.
        addToContact = single && !special && !firstNumber.isNullOrEmpty() && !singleNumberIsSaved && !isRecycleBin,
        copyNumber = single && !special && !firstNumber.isNullOrEmpty() && !isRecycleBin,
        // One sender only: a group has no single sender to report. Hidden once a block rule covers the sender:
        // reporting again would only say "Already blocked as spam".
        // B14: a saved contact's texts reach the inbox anyway, so "Always allow" would change nothing.
        allowSender = single && !address.isNullOrEmpty() && address != allowedSender && !singleNumberIsSaved &&
            !isRecycleBin,
        whyDecided = single && !address.isNullOrEmpty() && (address == allowedSender || address == blockedSender) &&
            !isRecycleBin,
        reportSpam = single && !address.isNullOrEmpty() && address != reportedSender && !isRecycleBin,
        search = hasItems && !isRecycleBin,
        alwaysForward = forwardingAvailable && single && !address.isNullOrEmpty() && !isRecycleBin,
        shareAsText = hasItems && !isRecycleBin,
        exportConversation = hasItems && !isRecycleBin,
        addToHomeScreen = pinShortcutSupported && hasConversation && !isRecycleBin,
        labels = hasConversation && !isRecycleBin,
        mute = hasConversation && !isRecycleBin && !muted,
        unmute = hasConversation && !isRecycleBin && muted,
        hide = hasConversation && !isRecycleBin && !hiddenChat,
        unhide = hasConversation && !isRecycleBin && hiddenChat,
    )
}

/**
 * The SIM to send from, as an index into [subscriptionIds] (the active SIMs in order): the one the user picked for
 * this number, else the one the last received message came in on, else the app's default SIM, else the phone's
 * default for SMS, else the first.
 *
 * [userPreferredSimId] is the stored choice for the first number (0 when none); [lastReceivedSubscriptionId] is
 * null unless the newest message was received; a negative default means none.
 */
fun chooseSimIndex(
    subscriptionIds: List<Int>,
    userPreferredSimId: Int,
    lastReceivedSubscriptionId: Int?,
    appDefaultId: Int,
    systemDefaultId: Int,
): Int {
    fun indexOf(id: Int): Int? = subscriptionIds.indexOf(id).takeIf { it >= 0 }
    val user = indexOf(userPreferredSimId)
    val sender = lastReceivedSubscriptionId?.let { indexOf(it) }
    val defaultId = if (appDefaultId >= 0) appDefaultId else systemDefaultId
    val system = if (defaultId >= 0) indexOf(defaultId) else null
    return user ?: sender ?: system ?: 0
}

/**
 * Whether the composed message goes as MMS: with an attachment, as a group MMS when the user chose that, or as a
 * long text the settings send as MMS.
 */
fun sendsAsMms(hasAttachments: Boolean, recipientCount: Int, sendGroupAsMms: Boolean, isLongText: Boolean): Boolean =
    hasAttachments || (recipientCount > 1 && sendGroupAsMms) || isLongText
