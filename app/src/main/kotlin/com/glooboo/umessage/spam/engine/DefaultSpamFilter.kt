package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode

class DefaultSpamFilter(
    private val rules: () -> List<SpamRule>,
    private val settings: () -> SpamSettings,
    private val contacts: ContactChecker,
    private val normalizer: NumberNormalizer,
    private val legacy: LegacyBlocklist = NoLegacyBlocklist,
    /** Country of the SIM that received a message, used to read national-format senders. Null = default region. */
    private val regionForSubscription: (Int) -> String? = { null },
    /** Provider truth: the group rules a sender is a member of (D3 A). */
    groups: GroupMembership = GroupMembership { emptyList() },
    /** Provider truth (R6, S8): the countries of every SIM, the one region list numbers are read with. */
    simRegions: () -> List<String> = { emptyList() },
) : SpamFilter {

    /** Number, sender id, keyword and pattern matching, shared with the forward rules. */
    private val matcher = RuleMatcher(normalizer, regionForSubscription, simRegions = simRegions)

    /**
     * Provider truth (section 2.1): the sender-based order. Used by [evaluate] as a probe that writes nothing; the
     * receivers write the facts through [SenderJudge.judge].
     */
    val senderJudge = SenderJudge(
        rules = rules,
        settings = settings,
        contacts = contacts,
        readers = NumberReaders(normalizer, simRegions),
        groups = groups,
        legacy = legacy,
        matcher = matcher,
    )

    override fun evaluate(message: IncomingMessage): FilterDecision = senderJudge.probe(message).decision()
}

/** The mode the filter really runs in: the old "block unknown numbers" switch turns an Off filter into Strict. */
internal fun effectiveUnknownSenderMode(mode: UnknownSenderMode, legacyBlockUnknown: Boolean): UnknownSenderMode =
    if (mode == UnknownSenderMode.OFF && legacyBlockUnknown) UnknownSenderMode.STRICT else mode
