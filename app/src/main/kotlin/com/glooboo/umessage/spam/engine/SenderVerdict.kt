package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
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

/** The members of both group rules, read once per [SenderJudge.snapshot]. */
fun interface GroupMembership {
    fun members(): List<AllowGroupMember>
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
     * The allowed or blocked word that made [fact] (null for the old word list, a code, or no fact). Step D counts its
     * hit with DefaultSpamRepository.recordWordRuleHit once the text is stored.
     */
    val wordRuleId: Long?
        get() = when (val made = fact) {
            is SenderFact.AutoAllow -> made.wordRuleId
            is SenderFact.AutoBlock -> made.wordRuleId
            else -> null
        }

    /**
     * The decision in the shape of the 0.4.14 filter: Allow or Block, with the word rule that decided when a text made
     * the fact, so the decision log and the rule hit count name the word rule as before. A fact that could not be
     * written is always [DecisionReason.FACT_FAILED] (A-11), whatever the fact was.
     */
    fun decision(): FilterDecision {
        val place = verdict
        return when {
            failOpen -> FilterDecision.Allow(DecisionReason.FACT_FAILED)
            place is SenderVerdict.Spam && fact is SenderFact.AutoBlock ->
                FilterDecision.Block(DecisionReason.BLOCK_RULE, fact.wordRuleId)
            place is SenderVerdict.Spam -> FilterDecision.Block(place.reason, place.ruleId)
            fact is SenderFact.AutoAllow -> FilterDecision.Allow(ALLOW_RULE, fact.wordRuleId)
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
 * Checks 4 and 5 are for phone numbers only (A-5, as in 0.4.14): a sender name is never a contact, so with contacts
 * unreadable a sender name is still judged by checks 6 to 11. Only checks 6, 7 and 10 read the text. [snapshot] reads
 * the rules, members, settings and SIM countries once and answers for any number of senders (the chat list);
 * [verdict] is one snapshot for one sender; [judge] writes the text's fact first and then asks a fresh snapshot (R1:
 * one verdict); [probe] says what [judge] would do without writing anything (the test sheet, the MMS push gate).
 * Never throws for a failed contacts or block-list lookup: those fail open.
 */
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

    /** Everything the sender checks read, read once. Build one per batch; it does not see later changes. */
    fun snapshot(): Snapshot = Snapshot(
        rules = runCatching { rules() }.getOrDefault(emptyList()),
        settings = settings(),
        members = runCatching { groups.members() }.getOrDefault(emptyList()),
        readers = readers.frozen(),
    )

    /** The place of [address]'s chat from the sender alone: checks 1 to 5, 8, 9 and 11. */
    fun verdict(address: String): SenderVerdict = snapshot().verdict(address)

    /** What [judge] would decide for [message], without writing anything. */
    fun probe(message: IncomingMessage): Judgement {
        val snap = snapshot()
        snap.senderChecks(message.address)?.let { return Judgement(it) }
        val fact = textFact(snap, message) ?: return Judgement(snap.afterText(message.address))
        val place = when (fact) {
            is SenderFact.AutoAllow -> SenderVerdict.Messages(ALLOW_RULE, fact.wordRuleId)
            is SenderFact.AutoBlock ->
                SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, fact.wordRuleId)
            SenderFact.JoinCodeGroup -> SenderVerdict.Messages(DecisionReason.GROUP_MEMBER)
        }
        return Judgement(place, fact, factWritten = true)
    }

    /**
     * Facts first (R1): the fact the text makes is written through [writer] (tried twice), then the place is read from
     * the sender alone with a fresh snapshot, so the text lands where its chat is. A fact that cannot be written gives
     * Messages with [DecisionReason.FACT_FAILED] (fail open: unread, announced; the caller logs it).
     */
    fun judge(message: IncomingMessage, writer: SenderFactWriter): Judgement {
        val snap = snapshot()
        snap.senderChecks(message.address)?.let { return Judgement(it) }
        val fact = textFact(snap, message) ?: return Judgement(snap.afterText(message.address))
        val written = write(writer, message.address, fact) || write(writer, message.address, fact)
        if (!written) return Judgement(SenderVerdict.Messages(DecisionReason.FACT_FAILED), fact)
        return Judgement(verdict(message.address), fact, factWritten = true)
    }

    private fun write(writer: SenderFactWriter, address: String, fact: SenderFact): Boolean =
        runCatching { writer.write(address, fact) }.getOrDefault(false)

    /** Checks 6, 7 and 10 (10 only when 8 and 9 do not decide). Null when the text makes no fact. */
    private fun textFact(snap: Snapshot, message: IncomingMessage): SenderFact? {
        val numbers = snap.readers.readers()
        snap.textRules.firstOrNull { it.kind == ListKind.ALLOW && matcher.matches(it, message, numbers) }
            ?.let { return SenderFact.AutoAllow(it.id) }
        snap.textRules.firstOrNull { it.kind == ListKind.BLOCK && matcher.matches(it, message, numbers) }
            ?.let { return SenderFact.AutoBlock(it.id) }
        return when {
            onLegacyWordList(message.body) -> SenderFact.AutoBlock(null)
            snap.automaticChecks(message.address) != null -> null
            snap.settings.codeGroupOn && allowingCode(message.body) -> SenderFact.JoinCodeGroup
            else -> null
        }
    }

    /** The old Fossify word list, read like a blocked keyword; blank entries never match. */
    private fun onLegacyWordList(body: String): Boolean {
        val words = runCatching { legacy.blockedKeywords() }.getOrDefault(emptyList())
        return words.any { it.isNotBlank() && TextNormalizer.containsObfuscated(body, it) }
    }

    /**
     * The rules, members, settings and region list of one moment (A-3). Exact-number rules and members are indexed by
     * the keys of every reading of the list, so one verdict costs a few map lookups. A member is also found under the
     * keys of the address it was stored with, read with today's list (A-6): a SIM change does not lose a member.
     */
    inner class Snapshot internal constructor(
        rules: List<SpamRule>,
        internal val settings: SpamSettings,
        members: List<AllowGroupMember>,
        internal val readers: NumberReaders,
    ) {
        internal val textRules = rules.filter { it.type in TEXT_TYPES }

        /** NUMBER rules by every key of their pattern. */
        private val numberRules: Map<String, List<SpamRule>> = buildMap<String, MutableList<SpamRule>> {
            rules.filter { it.type == RuleType.NUMBER && !matcherNormalizer.isSenderId(it.pattern) }.forEach { rule ->
                readers.senderKeys(rule.pattern).forEach { getOrPut(it) { mutableListOf() } += rule }
            }
        }

        /** Sender-name and prefix rules, matched one by one (few, and cheap string checks). */
        private val otherSenderRules = rules.filter { it.type == RuleType.SENDER_ID || it.type == RuleType.PREFIX }

        private val memberGroups: Map<String, Set<AllowGroup>> = buildMap<String, MutableSet<AllowGroup>> {
            members.forEach { member ->
                (readers.senderKeys(member.address) + member.senderKey).filter { it.isNotEmpty() }
                    .forEach { getOrPut(it) { mutableSetOf() } += member.group }
            }
        }

        /** The place of [address]'s chat: checks 1 to 5, 8, 9 and 11. */
        fun verdict(address: String): SenderVerdict = senderChecks(address) ?: afterText(address)

        /** The place when the text made no fact: checks 8, 9 and 11. */
        internal fun afterText(address: String): SenderVerdict =
            automaticChecks(address) ?: SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)

        /** Checks 1 to 5. Null when none of them decides. */
        internal fun senderChecks(address: String): SenderVerdict? {
            if (SenderAddress.isUnreadable(address)) return SenderVerdict.Messages(DecisionReason.UNREADABLE_SENDER)
            val named = rulesNaming(address)
            val userAllow = named.firstOrNull { it.kind == ListKind.ALLOW && !isAutomatic(it) }
            val block = named.firstOrNull { it.kind == ListKind.BLOCK }
            return when {
                userAllow != null -> SenderVerdict.Messages(ALLOW_RULE, userAllow.id)
                block != null -> SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, block.id)
                onLegacyNumberList(address) -> SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)
                else -> contactCheck(address)
            }
        }

        /** Checks 8 and 9. Null when neither decides. */
        internal fun automaticChecks(address: String): SenderVerdict? {
            val automaticAllow = rulesNaming(address).firstOrNull { it.kind == ListKind.ALLOW && isAutomatic(it) }
            val member = readers.senderKeys(address).flatMap { memberGroups[it].orEmpty() }
            return when {
                automaticAllow != null -> SenderVerdict.Messages(ALLOW_RULE, automaticAllow.id)
                member.any(settings::groupOn) -> SenderVerdict.Messages(DecisionReason.GROUP_MEMBER)
                !settings.filterOn -> SenderVerdict.Messages(DecisionReason.MODE_OFF)
                else -> null
            }
        }

        /** Rules that name the sender itself (number, sender name, prefix), in every reading of the region list. */
        private fun rulesNaming(address: String): List<SpamRule> {
            val numbered = readers.senderKeys(address).flatMap { numberRules[it].orEmpty() }
            val probe = IncomingMessage(address = address, body = "", subscriptionId = NO_SIM)
            val numbers = readers.readers()
            return (numbered + otherSenderRules.filter { matcher.matches(it, probe, numbers) }).distinct()
        }

        /** Checks 4 and 5, for phone numbers only (A-5). */
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

        private fun canReadContacts(): Boolean = runCatching { contacts.canReadContacts() }.getOrDefault(false)
    }

    private fun isAutomatic(rule: SpamRule) = RuleNotes.isMadeByFilter(rule.note)

    /** Android's own block list and the old Fossify number list. A failure is "not blocked" (fail open). */
    private fun onLegacyNumberList(address: String): Boolean =
        runCatching { legacy.isNumberBlocked(address) }.getOrDefault(false)

    private companion object {
        /** No receiving SIM: the verdict is the same on both SIMs. */
        const val NO_SIM = -1
        val TEXT_TYPES = setOf(RuleType.KEYWORD, RuleType.REGEX, RuleType.DOMAIN)

        /** Tells a sender name from a number; needs no region. */
        val matcherNormalizer = NumberNormalizer { "" }
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
