package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.DecisionReason.ALLOW_RULE
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.ui.spam.RuleNotes

/** Why a sender's chat is in Spam: the user (or a blocked word) blocked it, or nobody knows the sender. */
enum class SpamCause { BLOCKED, UNKNOWN }

/**
 * Where one sender's chat lives (provider truth, section 2.1). The same answer sorts a new text and the chat list, so
 * a text always follows its chat (R1). [reason] and [ruleId] say why, for the decision log.
 */
sealed interface SenderVerdict {
    val reason: DecisionReason
    val ruleId: Long?

    data class Messages(override val reason: DecisionReason, override val ruleId: Long? = null) : SenderVerdict

    data class Spam(
        val cause: SpamCause,
        override val reason: DecisionReason,
        override val ruleId: Long? = null,
    ) : SenderVerdict
}

/**
 * A fact about a sender that a text makes (checks 6, 7 and 10, section 2.2). It is written before the text is stored;
 * the place of the text is then read from the sender alone. Every write is idempotent.
 */
sealed interface SenderFact {
    /** Check 6: an allowed word, pattern or website ([wordRuleId]) allows the sender automatically (H4 A). */
    data class AutoAllow(val wordRuleId: Long) : SenderFact

    /** Check 7: a blocked word ([wordRuleId]; null for the old Fossify word list) blocks the sender (M-SP1). */
    data class AutoBlock(val wordRuleId: Long?) : SenderFact

    /** Check 10: a verification code ([AllowingCode]) puts the sender in "Senders of a verification code". */
    data object JoinCodeGroup : SenderFact
}

/** Writes a [SenderFact] for a sender. False when it could not be written; it is then tried once more. */
fun interface SenderFactWriter {
    fun write(address: String, fact: SenderFact): Boolean
}

/** The group rules a sender is a member of, by any of the keys it may be stored under ([NumberReaders.senderKeys]). */
fun interface GroupMembership {
    fun groupsOf(keys: Set<String>): Set<AllowGroup>
}

/** What [SenderJudge] decided for one text: the sender's place after the fact ([fact]) was written. */
data class Judgement(
    val verdict: SenderVerdict,
    val fact: SenderFact? = null,
    /** The fact was stored (or would be, for a probe). False when there was none or it could not be written. */
    val factWritten: Boolean = false,
) {
    /** R1 fail-open: a fact could not be written; the text goes to Messages, unread and announced. */
    val failOpen: Boolean get() = verdict.reason == DecisionReason.FACT_FAILED

    /** Stored read and silent, in Spam. Everything else is stored as today (unread, announced). */
    val inSpam: Boolean get() = verdict is SenderVerdict.Spam

    /**
     * The decision in the shape of the 0.4.14 filter: Allow or Block, with the word rule that decided when a text made
     * the fact, so the decision log and the rule hit count name the word rule as before.
     */
    fun decision(): FilterDecision {
        val place = verdict
        return when {
            place is SenderVerdict.Spam && fact is SenderFact.AutoBlock ->
                FilterDecision.Block(DecisionReason.BLOCK_RULE, fact.wordRuleId)
            place is SenderVerdict.Spam -> FilterDecision.Block(place.reason, place.ruleId)
            fact is SenderFact.AutoAllow -> FilterDecision.Allow(DecisionReason.ALLOW_RULE, fact.wordRuleId)
            fact == SenderFact.JoinCodeGroup && factWritten -> FilterDecision.Allow(DecisionReason.OTP_EXEMPTION)
            else -> FilterDecision.Allow(place.reason, place.ruleId.takeIf { place.reason == ALLOW_RULE })
        }
    }
}

/**
 * The filter of provider truth (section 2.1), in order:
 *
 * 1. The sender cannot be read (blank, or the "Unknown" placeholder, R7): Messages. No fact, no group.
 * 2. An allow rule the user made names the sender (number, sender name, prefix): Messages.
 * 3. A block rule names the sender (number, sender name, prefix, made by the user or by a blocked word), or Android's
 *    own block list or the old Fossify number list has it: Spam, cause BLOCKED.
 * 4. The sender is a contact: Messages.
 * 5. Contacts cannot be read (no permission, a failed lookup): Messages, fail open.
 * 6. The text holds an allowed word, pattern or website: fact [SenderFact.AutoAllow].
 * 7. The text holds a blocked word, pattern or website, or a word of the old Fossify list: fact [SenderFact.AutoBlock].
 * 8. An automatic allow rule names the sender, or it is a member of a group rule that is on: Messages.
 * 9. The filter switch is off: Messages.
 * 10. The text is a verification code ([AllowingCode]) and the code group is on: fact [SenderFact.JoinCodeGroup].
 * 11. Anything else: Spam, cause UNKNOWN.
 *
 * Checks 4 and 5 ask about phone numbers only: a sender name is never a contact. Only checks 6, 7 and 10 read the text;
 * [verdict] answers for the sender alone (the chat list), [judge] writes the text's fact first and then asks
 * [verdict] (R1: one verdict), [probe] says what [judge] would do without writing anything (the test sheet, the MMS
 * push gate, SenderBlocking's hint). Never throws for a failed contacts or block-list lookup: those fail open.
 */
@Suppress("TooManyFunctions") // One small function per check keeps each of them readable and testable.
class SenderJudge(
    private val rules: () -> List<SpamRule>,
    private val settings: () -> SpamSettings,
    private val contacts: ContactChecker,
    private val readers: NumberReaders,
    private val groups: GroupMembership,
    private val legacy: LegacyBlocklist = NoLegacyBlocklist,
    private val matcher: RuleMatcher = RuleMatcher(NumberNormalizer { "" }),
    private val allowingCode: (String) -> Boolean = AllowingCode::isAllowingCode,
) {

    /** The place of [address]'s chat from the sender alone: checks 1 to 5, 8, 9 and 11. */
    fun verdict(address: String): SenderVerdict {
        val current = rules()
        val now = settings()
        return senderChecks(address, current) ?: automaticChecks(address, current, now) ?: unknown()
    }

    /** What [judge] would decide for [message], without writing anything. */
    fun probe(message: IncomingMessage): Judgement {
        val current = rules()
        val now = settings()
        senderChecks(message.address, current)?.let { return Judgement(it) }
        val fact = textFact(message, current, now)
            ?: return Judgement(automaticChecks(message.address, current, now) ?: unknown())
        val place = when (fact) {
            is SenderFact.AutoAllow -> SenderVerdict.Messages(DecisionReason.ALLOW_RULE, fact.wordRuleId)
            is SenderFact.AutoBlock ->
                SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, fact.wordRuleId)
            SenderFact.JoinCodeGroup -> SenderVerdict.Messages(DecisionReason.GROUP_MEMBER)
        }
        return Judgement(place, fact, factWritten = true)
    }

    /**
     * Facts first (R1): the fact the text makes is written through [writer] (tried twice), then the place is read from
     * the sender alone with [verdict], so the text lands where its chat is. A fact that cannot be written gives
     * Messages with [DecisionReason.FACT_FAILED] (fail open: unread, announced; the caller logs it).
     */
    fun judge(message: IncomingMessage, writer: SenderFactWriter): Judgement {
        val current = rules()
        val now = settings()
        senderChecks(message.address, current)?.let { return Judgement(it) }
        val fact = textFact(message, current, now)
            ?: return Judgement(automaticChecks(message.address, current, now) ?: unknown())
        val written = write(writer, message.address, fact) || write(writer, message.address, fact)
        if (!written) return Judgement(SenderVerdict.Messages(DecisionReason.FACT_FAILED), fact)
        return Judgement(verdict(message.address), fact, factWritten = true)
    }

    private fun write(writer: SenderFactWriter, address: String, fact: SenderFact): Boolean =
        runCatching { writer.write(address, fact) }.getOrDefault(false)

    /** Checks 1 to 5. Null when none of them decides. */
    private fun senderChecks(address: String, current: List<SpamRule>): SenderVerdict? {
        if (SenderAddress.isUnreadable(address)) return SenderVerdict.Messages(DecisionReason.UNREADABLE_SENDER)
        val named = rulesNaming(address, current)
        val userAllow = named.firstOrNull { it.kind == ListKind.ALLOW && !isAutomatic(it) }
        val block = named.firstOrNull { it.kind == ListKind.BLOCK }
        return when {
            userAllow != null -> SenderVerdict.Messages(DecisionReason.ALLOW_RULE, userAllow.id)
            block != null -> SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, block.id)
            onLegacyNumberList(address) -> SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)
            else -> contactCheck(address)
        }
    }

    /** Checks 4 and 5, for phone numbers. */
    private fun contactCheck(address: String): SenderVerdict? {
        if (matcherNormalizer.isSenderId(address)) return null
        val lookup = runCatching { contacts.lookup(address) }.getOrDefault(ContactLookup.UNKNOWN)
        return when {
            lookup == ContactLookup.FOUND -> SenderVerdict.Messages(DecisionReason.CONTACT)
            lookup == ContactLookup.UNKNOWN || !canReadContacts() ->
                SenderVerdict.Messages(DecisionReason.CONTACTS_UNAVAILABLE)
            else -> null
        }
    }

    /** Checks 6, 7 and 10 (10 only when 8 and 9 do not decide). Null when the text makes no fact. */
    private fun textFact(message: IncomingMessage, current: List<SpamRule>, now: SpamSettings): SenderFact? {
        val numbers = readers.readers()
        val textRules = current.filter { it.type in TEXT_TYPES }
        textRules.firstOrNull { it.kind == ListKind.ALLOW && matcher.matches(it, message, numbers) }
            ?.let { return SenderFact.AutoAllow(it.id) }
        textRules.firstOrNull { it.kind == ListKind.BLOCK && matcher.matches(it, message, numbers) }
            ?.let { return SenderFact.AutoBlock(it.id) }
        return when {
            onLegacyWordList(message.body) -> SenderFact.AutoBlock(null)
            automaticChecks(message.address, current, now) != null -> null
            now.codeGroupOn && allowingCode(message.body) -> SenderFact.JoinCodeGroup
            else -> null
        }
    }

    /** Checks 8 and 9. Null when neither decides. */
    private fun automaticChecks(address: String, current: List<SpamRule>, now: SpamSettings): SenderVerdict? {
        val automaticAllow = rulesNaming(address, current).firstOrNull { it.kind == ListKind.ALLOW && isAutomatic(it) }
        val member = runCatching { groups.groupsOf(readers.senderKeys(address)) }.getOrDefault(emptySet())
        return when {
            automaticAllow != null -> SenderVerdict.Messages(DecisionReason.ALLOW_RULE, automaticAllow.id)
            member.any(now::groupOn) -> SenderVerdict.Messages(DecisionReason.GROUP_MEMBER)
            !now.filterOn -> SenderVerdict.Messages(DecisionReason.MODE_OFF)
            else -> null
        }
    }

    private fun unknown() = SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)

    /** Rules that name the sender itself (number, sender name, prefix), in every reading of the region list. */
    private fun rulesNaming(address: String, current: List<SpamRule>): List<SpamRule> {
        val numbers = readers.readers()
        val probe = IncomingMessage(address = address, body = "", subscriptionId = NO_SIM)
        return current.filter { it.type in SENDER_TYPES && matcher.matches(it, probe, numbers) }
    }

    private fun isAutomatic(rule: SpamRule) = RuleNotes.isMadeByFilter(rule.note)

    private fun canReadContacts(): Boolean = runCatching { contacts.canReadContacts() }.getOrDefault(false)

    /** Android's own block list and the old Fossify number list. A failure is "not blocked" (fail open). */
    private fun onLegacyNumberList(address: String): Boolean =
        runCatching { legacy.isNumberBlocked(address) }.getOrDefault(false)

    /** The old Fossify word list, read like a blocked keyword; blank entries never match. */
    private fun onLegacyWordList(body: String): Boolean {
        val words = runCatching { legacy.blockedKeywords() }.getOrDefault(emptyList())
        return words.any { it.isNotBlank() && TextNormalizer.containsObfuscated(body, it) }
    }

    private companion object {
        /** Tells a sender name from a number; needs no region. */
        val matcherNormalizer = NumberNormalizer { "" }

        /** No receiving SIM: the verdict is the same on both SIMs. */
        const val NO_SIM = -1
        val SENDER_TYPES = setOf(RuleType.NUMBER, RuleType.SENDER_ID, RuleType.PREFIX)
        val TEXT_TYPES = setOf(RuleType.KEYWORD, RuleType.REGEX, RuleType.DOMAIN)
    }
}

/** R7: a sender that cannot be read. */
object SenderAddress {
    /** The placeholder the SMS receiver stores for a text without a sender (SmsReceiver.UNKNOWN_SENDER). */
    const val UNKNOWN_PLACEHOLDER = "Unknown"

    /** Blank, or the "Unknown" placeholder: such a text is shown in Messages, and its sender gets no rule or group. */
    fun isUnreadable(address: String): Boolean {
        val value = address.trim()
        return value.isEmpty() || value.equals(UNKNOWN_PLACEHOLDER, ignoreCase = true)
    }
}

/**
 * R1: the check after every insert (SMS, MMS and the replay of the pending queue). A row stored read and silent
 * whose thread is not in Spam must be marked unread and announced, so no text is ever read and silent in Messages.
 */
object SpamSafetyCheck {
    fun mustAnnounce(storedSilently: Boolean, threadInSpam: Boolean): Boolean = storedSilently && !threadInSpam
}
