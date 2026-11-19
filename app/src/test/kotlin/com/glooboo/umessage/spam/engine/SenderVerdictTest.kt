package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.spam.RuleNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter order of provider truth (section 2.1), one test per row in order, and the R1 cases of section 2.2: the
 * fact a text makes is written first, and the text then lands where the sender's chat is ([SenderJudge.verdict]).
 * Every number and text is made up.
 */
class SenderVerdictTest {

    /** Rules, members and contacts in memory, and a fact writer that behaves like the repository's. */
    private class World(
        region: String = "US",
        simRegions: List<String> = emptyList(),
        var settings: SpamSettings = SpamSettings(),
        var contacts: Set<String> = emptySet(),
        var contactsReadable: Boolean = true,
        var lookupFails: Boolean = false,
        var legacyNumbers: Set<String> = emptySet(),
        var legacyWords: List<String> = emptyList(),
    ) {
        val normalizer = NumberNormalizer { region }
        val readers = NumberReaders(normalizer) { simRegions }
        val rules = mutableListOf<SpamRule>()
        val members = mutableMapOf<String, MutableSet<AllowGroup>>()

        /** Members as an older SIM list stored them (A-6). */
        val stored = mutableListOf<AllowGroupMember>()
        var refusals = 0
        var writes = 0
        private var nextId = 100L

        fun rule(kind: ListKind, type: RuleType, pattern: String, note: String = ""): Long {
            val id = nextId++
            rules += SpamRule(id = id, kind = kind, type = type, pattern = pattern, note = note)
            return id
        }

        fun join(group: AllowGroup, address: String) {
            members.getOrPut(readers.senderKey(address)) { mutableSetOf() } += group
        }

        fun groupsOf(address: String): Set<AllowGroup> =
            readers.senderKeys(address).flatMap { members[it].orEmpty() }.toSet()

        private val contactChecker = object : ContactChecker {
            override fun isContact(address: String) = contacts.any { readers.same(it, address) }
            override fun lookup(address: String): ContactLookup = when {
                lookupFails -> ContactLookup.UNKNOWN
                isContact(address) -> ContactLookup.FOUND
                else -> ContactLookup.NOT_FOUND
            }
            override fun canReadContacts() = contactsReadable
        }

        private val legacy = object : LegacyBlocklist {
            override fun isNumberBlocked(address: String) = address in legacyNumbers
            override fun blockedKeywords() = legacyWords
            override fun blockUnknownNumbers() = false
        }

        val writer = SenderFactWriter { address, fact ->
            writes++
            if (refusals > 0) {
                refusals--
                return@SenderFactWriter false
            }
            val type = if (normalizer.isSenderId(address)) RuleType.SENDER_ID else RuleType.NUMBER
            val pattern = readers.rulePattern(address)
            when (fact) {
                is SenderFact.AutoAllow ->
                    if (rules.none { it.kind == ListKind.ALLOW && it.pattern == pattern }) {
                        rule(ListKind.ALLOW, type, pattern, RuleNotes.ALLOWED_FROM_KEYWORD)
                    }
                is SenderFact.AutoBlock -> {
                    rules.removeAll {
                        it.kind == ListKind.ALLOW && it.pattern == pattern && RuleNotes.isMadeByFilter(it.note)
                    }
                    readers.senderKeys(address).forEach { members.remove(it) }
                    if (rules.none { it.kind == ListKind.BLOCK && it.pattern == pattern }) {
                        rule(ListKind.BLOCK, type, pattern, RuleNotes.BLOCKED_FROM_KEYWORD)
                    }
                }
                SenderFact.JoinCodeGroup -> join(AllowGroup.CODE, address)
            }
            true
        }

        val judge = SenderJudge(
            rules = { rules.toList() },
            settings = { settings },
            contacts = contactChecker,
            readers = readers,
            groups = GroupMembership {
                members.flatMap { (key, groups) -> groups.map { AllowGroupMember(it, key, key) } } + stored
            },
            legacy = legacy,
            matcher = RuleMatcher(normalizer),
        )

        /** Judges [body] from [address], and checks R1: the text lands where the sender's chat is. */
        fun receive(address: String, body: String = "hello", sub: Int = 1): Judgement {
            val judgement = judge.judge(IncomingMessage(address, body, sub), writer)
            if (!judgement.failOpen) assertEquals("text follows chat", judgement.verdict, judge.verdict(address))
            return judgement
        }
    }

    private fun assertMessages(reason: DecisionReason, judgement: Judgement) {
        val verdict = judgement.verdict
        assertTrue("expected Messages but was $verdict", verdict is SenderVerdict.Messages)
        assertEquals(reason, verdict.reason)
    }

    private fun assertSpam(cause: SpamCause, judgement: Judgement) {
        val verdict = judgement.verdict
        assertTrue("expected Spam but was $verdict", verdict is SenderVerdict.Spam)
        assertEquals(cause, (verdict as SenderVerdict.Spam).cause)
    }

    // Row 1 (R7): a sender that cannot be read.

    @Test fun row1_aSenderlessTextIsShownAndMakesNoFact() {
        val world = World()
        world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        listOf("", "  ", "Unknown", "unknown").forEach { address ->
            assertMessages(DecisionReason.UNREADABLE_SENDER, world.receive(address, "You won the lottery"))
            assertMessages(DecisionReason.UNREADABLE_SENDER, world.receive(address, "Your verification code is 482915"))
        }
        assertEquals(0, world.writes)
        assertTrue(world.members.isEmpty())
    }

    // Row 2: an allow rule the user made.

    @Test fun row2_theUsersAllowBeatsABlockAndABlockedWord() {
        val world = World()
        val allow = world.rule(ListKind.ALLOW, RuleType.NUMBER, SENDER)
        world.rule(ListKind.BLOCK, RuleType.NUMBER, SENDER)
        world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        val judgement = world.receive(SENDER, "You won the lottery")
        assertMessages(DecisionReason.ALLOW_RULE, judgement)
        assertEquals(allow, judgement.verdict.ruleId)
        assertNull(judgement.fact)
    }

    @Test fun row2_theUsersPrefixAndSenderNameAllowsCount() {
        val world = World()
        world.rule(ListKind.ALLOW, RuleType.PREFIX, "+1555000")
        world.rule(ListKind.ALLOW, RuleType.SENDER_ID, "MYBANK", note = RuleNotes.ALLOWED_BY_YOU)
        assertMessages(DecisionReason.ALLOW_RULE, world.receive(SENDER))
        assertMessages(DecisionReason.ALLOW_RULE, world.receive("MyBank"))
    }

    // Row 3: a block rule names the sender.

    @Test fun row3_aBlockedSenderIsInSpamAlsoForACode() {
        val world = World()
        world.rule(ListKind.BLOCK, RuleType.NUMBER, SENDER)
        val judgement = world.receive(SENDER, "Your verification code is 482915")
        assertSpam(SpamCause.BLOCKED, judgement)
        assertNull("a blocked sender joins no group", judgement.fact)
        assertTrue(world.groupsOf(SENDER).isEmpty())
    }

    @Test fun row3_aBlockedContactIsInSpam() {
        val world = World(contacts = setOf(SENDER))
        world.rule(ListKind.BLOCK, RuleType.NUMBER, SENDER)
        assertSpam(SpamCause.BLOCKED, world.receive(SENDER))
    }

    @Test fun row3_androidsBlockListAndAPrefixBlock() {
        val world = World(legacyNumbers = setOf(SENDER))
        assertSpam(SpamCause.BLOCKED, world.receive(SENDER))
        world.rule(ListKind.BLOCK, RuleType.PREFIX, "+1555999")
        assertSpam(SpamCause.BLOCKED, world.receive("+15559990000"))
    }

    @Test fun row3_aBlockBeatsAGroupMemberAndAnAllowedWordAllow() {
        val world = World()
        world.join(AllowGroup.CODE, SENDER)
        world.rule(ListKind.ALLOW, RuleType.NUMBER, OTHER, note = RuleNotes.ALLOWED_FROM_KEYWORD)
        world.rule(ListKind.BLOCK, RuleType.NUMBER, SENDER)
        world.rule(ListKind.BLOCK, RuleType.NUMBER, OTHER)
        assertSpam(SpamCause.BLOCKED, world.receive(SENDER))
        assertSpam(SpamCause.BLOCKED, world.receive(OTHER))
    }

    // Row 4: a contact.

    @Test fun row4_aContactIsShownAndExemptFromABlockedWord() {
        val world = World(contacts = setOf(SENDER))
        world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        val judgement = world.receive(SENDER, "Lottery night at our place")
        assertMessages(DecisionReason.CONTACT, judgement)
        assertNull(judgement.fact)
        assertTrue(world.rules.none { it.type == RuleType.NUMBER })
    }

    // Row 5: contacts cannot be read.

    @Test fun row5_unreadableContactsFailOpenAndABlockedWordBlocksNobody() {
        val noPermission = World(contactsReadable = false)
        noPermission.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        assertMessages(DecisionReason.CONTACTS_UNAVAILABLE, noPermission.receive(SENDER, "lottery"))
        assertEquals(0, noPermission.writes)

        val failing = World(lookupFails = true)
        assertMessages(DecisionReason.CONTACTS_UNAVAILABLE, failing.receive(SENDER))
    }

    @Test fun row5_aSenderNameIsNeverAContactSoItIsJudgedOn() {
        val world = World(contactsReadable = false)
        assertSpam(SpamCause.UNKNOWN, world.receive("PROMOS", "Big sale today"))
    }

    // Row 6 (H4 A): an allowed word allows the sender automatically, numbers and sender names.

    @Test fun row6_anAllowedWordAllowsTheSenderForGood() {
        val world = World()
        val word = world.rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        val judgement = world.receive(SENDER, "Your parcel is on its way")
        assertMessages(DecisionReason.ALLOW_RULE, judgement)
        assertEquals(SenderFact.AutoAllow(word), judgement.fact)
        assertEquals(FilterDecision.Allow(DecisionReason.ALLOW_RULE, word), judgement.decision())
        val auto = world.rules.single { it.type == RuleType.NUMBER }
        assertEquals(RuleNotes.ALLOWED_FROM_KEYWORD, auto.note)
        // The next text without the word still arrives in Messages.
        assertMessages(DecisionReason.ALLOW_RULE, world.receive(SENDER, "Hello again"))
    }

    @Test fun row6_alsoForASenderName() {
        val world = World()
        world.rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertMessages(DecisionReason.ALLOW_RULE, world.receive("ParcelCo", "Your parcel is on its way"))
        assertEquals(RuleType.SENDER_ID, world.rules.last().type)
        assertMessages(DecisionReason.ALLOW_RULE, world.receive("ParcelCo", "Anything"))
    }

    // Row 7 (M-SP1): a blocked word, and the old Fossify word list, block the sender.

    @Test fun row7_aBlockedWordBlocksTheSender() {
        val world = World()
        val word = world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        val judgement = world.receive(SENDER, "You won the lottery")
        assertSpam(SpamCause.BLOCKED, judgement)
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, word), judgement.decision())
        assertEquals(RuleNotes.BLOCKED_FROM_KEYWORD, world.rules.last().note)
        assertSpam(SpamCause.BLOCKED, world.receive(SENDER, "Hello"))
    }

    @Test fun row7_theOldWordListWritesABlockRule() {
        val world = World(legacyWords = listOf("casino"))
        val judgement = world.receive(SENDER, "Best casino bonus")
        assertSpam(SpamCause.BLOCKED, judgement)
        assertEquals(SenderFact.AutoBlock(null), judgement.fact)
        assertTrue(world.rules.any { it.kind == ListKind.BLOCK && it.type == RuleType.NUMBER })
    }

    @Test fun row7_aBlockedWordFromAGroupMemberOrAnAutoAllowedSenderBlocksIt() {
        val world = World()
        world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        world.join(AllowGroup.CODE, SENDER)
        world.rule(ListKind.ALLOW, RuleType.NUMBER, OTHER, note = RuleNotes.ALLOWED_FROM_KEYWORD)
        assertSpam(SpamCause.BLOCKED, world.receive(SENDER, "lottery"))
        assertTrue("membership removed", world.groupsOf(SENDER).isEmpty())
        assertSpam(SpamCause.BLOCKED, world.receive(OTHER, "lottery"))
        assertTrue("automatic allow removed", world.rules.none { it.kind == ListKind.ALLOW })
    }

    // Row 8: automatically allowed (a group member, or allowed earlier by an allowed word).

    @Test fun row8_groupMembersAreShownWhileTheirGroupIsOn() {
        val world = World()
        world.join(AllowGroup.CODE, SENDER)
        world.join(AllowGroup.WROTE, OTHER)
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(SENDER))
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(OTHER))

        world.settings = SpamSettings(codeGroupOn = false)
        assertSpam(SpamCause.UNKNOWN, world.receive(SENDER))
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(OTHER))

        world.settings = SpamSettings(wroteGroupOn = false)
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(SENDER))
        assertSpam(SpamCause.UNKNOWN, world.receive(OTHER))
        assertTrue("members stay stored", world.groupsOf(OTHER).contains(AllowGroup.WROTE))
    }

    @Test fun row8_anAllowedWordAllowIsAutomatic() {
        val world = World()
        val auto = world.rule(ListKind.ALLOW, RuleType.NUMBER, SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD)
        val judgement = world.receive(SENDER)
        assertMessages(DecisionReason.ALLOW_RULE, judgement)
        assertEquals(auto, judgement.verdict.ruleId)
    }

    // Row 9: the filter is off.

    @Test fun row9_filterOffShowsEverythingButBlockedSenders() {
        val world = World(settings = SpamSettings(filterOn = false))
        world.rule(ListKind.BLOCK, RuleType.NUMBER, OTHER)
        assertMessages(DecisionReason.MODE_OFF, world.receive(SENDER))
        assertSpam(SpamCause.BLOCKED, world.receive(OTHER))
        // A code makes no membership while the filter is off (row 10 comes after row 9).
        assertMessages(DecisionReason.MODE_OFF, world.receive(SENDER, "Your verification code is 482915"))
        assertTrue(world.members.isEmpty())
    }

    // Row 10: a verification code puts the sender in the code group.

    @Test fun row10_aCodeWithOrWithoutALinkAllowsTheSender() {
        val world = World()
        val judgement = world.receive(SENDER, "Your verification code is 482915")
        assertMessages(DecisionReason.GROUP_MEMBER, judgement)
        assertEquals(SenderFact.JoinCodeGroup, judgement.fact)
        assertEquals(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), judgement.decision())
        assertEquals(setOf(AllowGroup.CODE), world.groupsOf(SENDER))
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(SENDER, "Weekend sale"))

        val link = World()
        assertMessages(
            DecisionReason.GROUP_MEMBER,
            link.receive(SENDER, "Your verification code is 482915. Help: example.com/help"),
        )
    }

    @Test fun row10_theCodeGroupOffOrANonCodeLeavesTheSenderUnknown() {
        val off = World(settings = SpamSettings(codeGroupOn = false))
        assertSpam(SpamCause.UNKNOWN, off.receive(SENDER, "Your verification code is 482915"))
        assertTrue(off.members.isEmpty())

        val tracking = World()
        assertSpam(SpamCause.UNKNOWN, tracking.receive(SENDER, "Tracking code 99887766 for your delivery"))
        assertTrue(tracking.members.isEmpty())
    }

    // Row 11: anything else.

    @Test fun row11_anUnknownSenderIsInSpam() {
        val judgement = World().receive(SENDER, "Hi, is this still for sale?")
        assertSpam(SpamCause.UNKNOWN, judgement)
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement.decision())
        assertFalse(judgement.factWritten)
    }

    // R1: every mismatch case ends in Spam (stored silently) or in Messages announced.

    @Test fun r1_aRefusedFactIsTriedOnceMoreThenFailsOpen() {
        val world = World()
        world.refusals = 2
        val judgement = world.receive(SENDER, "Your verification code is 482915")
        assertTrue(judgement.failOpen)
        assertFalse(judgement.inSpam)
        assertEquals(2, world.writes)
        assertMessages(DecisionReason.FACT_FAILED, judgement)
    }

    @Test fun r1_aFactRefusedOnceIsWrittenOnTheRetry() {
        val world = World()
        world.refusals = 1
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(SENDER, "Your verification code is 482915"))
        assertEquals(2, world.writes)
    }

    @Test fun r1_aThrowingWriterFailsOpenToo() {
        val world = World()
        world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        val throwing = SenderFactWriter { _, _ -> error("database locked") }
        val judgement = world.judge.judge(IncomingMessage(SENDER, "You won the lottery", 1), throwing)
        assertTrue(judgement.failOpen)
    }

    @Test fun r1_theOldWordListAgreesWithTheChatList() {
        val world = World(legacyWords = listOf("casino"))
        val judgement = world.receive(SENDER, "casino")
        assertTrue(judgement.inSpam)
        assertTrue(world.judge.verdict(SENDER) is SenderVerdict.Spam)
    }

    /** A process death between the fact and the insert: the replay judges again and writes nothing twice. */
    @Test fun r1_theReplayAfterADeathBetweenFactAndInsertGivesTheSamePlace() {
        val world = World()
        world.rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        val first = world.receive(SENDER, "Your parcel is here")
        val replay = world.receive(SENDER, "Your parcel is here")
        assertEquals(first.verdict.javaClass, replay.verdict.javaClass)
        assertEquals(1, world.rules.count { it.type == RuleType.NUMBER })

        val code = World()
        val a = code.receive(SENDER, "Your verification code is 482915")
        val b = code.receive(SENDER, "Your verification code is 482915")
        assertEquals(a.verdict, b.verdict)
        assertEquals(1, code.members.size)
    }

    /** One chat stored under two shapes of the same number: one place for both. */
    @Test fun r1_twoShapesOfOneNumberInOneThread() {
        val world = World(region = "US")
        world.receive("+15550001234", "Your verification code is 482915")
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive("(555) 000-1234"))
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive("5550001234"))
    }

    /** The SIM a text arrived on does not change the reading of the sender (R6, S8). */
    @Test fun r1_aRegionMismatchBetweenSimsDoesNotSplitASender() {
        val world = World(region = "US", simRegions = listOf("IR", "GB"))
        world.receive(IR_NATIONAL, "کد تایید شما: ۴۸۲۹۱۵", sub = 2)
        listOf(IR_NATIONAL, IR_PLUS, IR_00).forEach { shape ->
            assertMessages(DecisionReason.GROUP_MEMBER, world.receive(shape, "سلام", sub = 1))
            assertMessages(DecisionReason.GROUP_MEMBER, world.receive(shape, "سلام", sub = 2))
        }
    }

    /** A-6: a member stored under an older SIM list (key +98...) is still found when the list changed. */
    @Test fun aMemberIsFoundAfterTheSimRegionListChanged() {
        val world = World(region = "US", simRegions = listOf("GB"))
        world.stored += AllowGroupMember(AllowGroup.CODE, IR_PLUS, IR_NATIONAL, 1L, MemberSource.LIVE, true)
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(IR_NATIONAL))
        assertMessages(DecisionReason.GROUP_MEMBER, world.receive(IR_PLUS))
    }

    /** A-11: a failed allowed-word fact is logged as FACT_FAILED, not as the word's allow. */
    @Test fun aFailedAutoAllowIsFactFailed() {
        val world = World()
        world.rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        world.refusals = 2
        val judgement = world.receive(SENDER, "Your parcel is here")
        assertTrue(judgement.failOpen)
        assertEquals(FilterDecision.Allow(DecisionReason.FACT_FAILED), judgement.decision())
        assertEquals(100L, judgement.wordRuleId)
    }

    /** A-3: one snapshot sorts many chats; 5,000 verdicts stay well inside a soft bound on the JVM. */
    @Test fun fiveThousandVerdictsFromOneSnapshot() {
        val world = World(simRegions = listOf("IR"))
        repeat(200) { world.rule(ListKind.BLOCK, RuleType.NUMBER, "+1555%07d".format(it)) }
        world.rule(ListKind.BLOCK, RuleType.PREFIX, "+1555999")
        world.rule(ListKind.ALLOW, RuleType.SENDER_ID, "MYBANK")
        repeat(300) { world.join(AllowGroup.CODE, "+1555%07d".format(500_000 + it)) }
        val snapshot = world.judge.snapshot()
        val addresses = (0 until 5_000).map { "+1555%07d".format(it * 101) }
        // Warm up the number library and the JIT once, so the measured run is the snapshot's own work.
        addresses.take(WARM_UP).forEach { snapshot.verdict(it) }
        val start = System.nanoTime()
        val places = addresses.map(snapshot::verdict)
        val millis = (System.nanoTime() - start) / 1_000_000
        // A soft guard against a lost index only (a busy build machine took 2.1 s once); the work is asserted below.
        assertTrue("5,000 verdicts took $millis ms", millis < SOFT_BOUND_MS)
        assertEquals(places.size, 5_000)
        assertTrue(places.first() is SenderVerdict.Spam)
        assertEquals(world.judge.verdict(addresses[3]), places[3])
    }

    /** R-1: a failed rules read throws instead of judging on no rules (the caller fails open). */
    @Test fun aFailedRulesReadThrows() {
        val world = World()
        val broken = SenderJudge(
            rules = { error("rules table unreadable") },
            settings = { SpamSettings() },
            contacts = object : ContactChecker {
                override fun isContact(address: String) = false
            },
            readers = world.readers,
            groups = GroupMembership { emptyList() },
        )
        val failed = runCatching { broken.judge(IncomingMessage(SENDER, "hello", 1), world.writer) }
        assertTrue(failed.isFailure)
        assertTrue(runCatching { broken.verdict(SENDER) }.isFailure)
    }

    /**
     * R-6: the snapshot reads a NUMBER rule exactly as RuleMatcher does, also for short codes, a reader with no region
     * and "98906..." written without + under a US-only list.
     */
    @Test fun theSnapshotAndRuleMatcherAgreeOnNumberRules() {
        val cases = listOf(
            Triple("US", "30001234", listOf("30001234", "+130001234", "130001234")),
            Triple("", "+15550001234", listOf("5550001234", "05550001234", "+15550001234", "15550001234")),
            Triple("US", IR_PLUS, listOf("989061234567", IR_00, IR_NATIONAL, "+15550001234")),
            Triple("US", "989061234567", listOf(IR_PLUS, "989061234567")),
        )
        for ((region, pattern, addresses) in cases) {
            val world = World(region = region)
            val rule = SpamRule(id = 1, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = pattern)
            world.rules += rule
            val matcher = RuleMatcher(world.normalizer)
            addresses.forEach { address ->
                val expected = matcher.matches(rule, IncomingMessage(address, "", 1), world.readers.readers())
                val blocked = world.judge.verdict(address) is SenderVerdict.Spam &&
                    world.judge.verdict(address).reason == DecisionReason.BLOCK_RULE
                assertEquals("$pattern vs $address in '$region'", expected, blocked)
            }
        }
    }

    @Test fun r1_theSafetyCheckAnnouncesASilentRowOutsideSpam() {
        assertTrue(SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = false))
        assertFalse(SpamSafetyCheck.mustAnnounce(storedSilently = true, threadInSpam = true))
        assertFalse(SpamSafetyCheck.mustAnnounce(storedSilently = false, threadInSpam = false))
    }

    // Probes write nothing.

    @Test fun aProbeWritesNoFactButSaysWhereTheTextWouldGo() {
        val world = World()
        val word = world.rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery")
        val probe = world.judge.probe(IncomingMessage(SENDER, "lottery", 1))
        assertTrue(probe.inSpam)
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, word), probe.decision())
        val code = world.judge.probe(IncomingMessage(OTHER, "Your verification code is 482915", 1))
        assertEquals(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), code.decision())
        assertEquals(0, world.writes)
        assertTrue(world.members.isEmpty())
        assertEquals(1, world.rules.size)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val WARM_UP = 200
        const val SOFT_BOUND_MS = 6_000L
        const val OTHER = "+15550005678"
        const val IR_NATIONAL = "09061234567"
        const val IR_PLUS = "+989061234567"
        const val IR_00 = "00989061234567"
    }
}
