package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.spam.engine.SenderFact
import com.glooboo.umessage.ui.spam.RuleNotes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repository side of provider truth: rules for both SIMs (7.2), the facts a text writes first (R1, H4 A, M-SP1,
 * D3 A), the group members and their Undo (U5, M-SP3). Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class AllowGroupsRepositoryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    private val dao get() = db.spamDao()
    private val readers = NumberReaders(NumberNormalizer { "US" }) { listOf("IR") }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        val platform = object : SpamPlatform {
            override fun notifyBlocked(message: BlockedMessage) = Unit
            override fun deliverRestored(pending: PendingSmsEntity): Long? = null
            override fun deliverRestoredMms(message: BlockedMessage): Long? = null
            override fun removeRestored(id: Long, isMms: Boolean) = false
            override fun deleteMedia(names: Collection<String>) = Unit
            override fun sweepMedia(keep: Set<String>) = Unit
        }
        repo = DefaultSpamRepository(
            platform, db, SpamSettingsStore(app), NumberNormalizer { "US" },
            blockedFromMessageNote = { RuleNotes.BLOCKED_FROM_MESSAGE },
            blockedFromKeywordNote = { RuleNotes.BLOCKED_FROM_KEYWORD },
            allowedFromKeywordNote = { RuleNotes.ALLOWED_FROM_KEYWORD },
            simRegions = { listOf("IR") },
        )
    }

    @After fun tearDown() = db.close()

    private fun rulesFor(pattern: String) = dao.getRules().filter { it.pattern == pattern }

    private fun groupsOf(address: String) = repo.groupMembership.groupsOf(readers.senderKeys(address))

    @Test fun aRuleForOneSimIsStoredForBoth() = runTest {
        val prize = SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize")
        val id = repo.addRule(prize.copy(subscriptionId = 2))
        assertNull(dao.getRule(id)!!.subscriptionId)
        val again = repo.addRule(prize.copy(subscriptionId = 1))
        assertEquals(id, again)
        assertEquals(1, dao.getRules().size)
    }

    @Test fun anAllowedWordWritesOneAutomaticAllowRule() {
        assertTrue(repo.writeSenderFact(SENDER, SenderFact.AutoAllow(7)))
        assertTrue(repo.writeSenderFact(SENDER, SenderFact.AutoAllow(7)))
        val rule = rulesFor(SENDER).single()
        assertEquals(ListKind.ALLOW, rule.kind)
        assertEquals(RuleType.NUMBER, rule.type)
        assertEquals(RuleNotes.ALLOWED_FROM_KEYWORD, rule.note)
        assertFalse("shows its line until Keep", rule.acknowledged)
    }

    @Test fun anAllowedWordAllowsASenderNameToo() {
        assertTrue(repo.writeSenderFact("ParcelCo", SenderFact.AutoAllow(7)))
        assertEquals(RuleType.SENDER_ID, rulesFor("PARCELCO").single().type)
    }

    @Test fun aBlockedWordTakesAwayTheAutomaticAllowAndTheMembership() = runTest {
        repo.writeSenderFact(SENDER, SenderFact.AutoAllow(7))
        assertTrue(repo.joinGroup(AllowGroup.CODE, SENDER))
        assertTrue(repo.writeSenderFact(SENDER, SenderFact.AutoBlock(9)))
        val rule = rulesFor(SENDER).single()
        assertEquals(ListKind.BLOCK, rule.kind)
        assertEquals(RuleNotes.BLOCKED_FROM_KEYWORD, rule.note)
        assertTrue(groupsOf(SENDER).isEmpty())
    }

    @Test fun aCodeJoinsTheCodeGroupOnceUnderEveryShape() {
        assertTrue(repo.writeSenderFact(IR_NATIONAL, SenderFact.JoinCodeGroup))
        assertTrue(repo.writeSenderFact(IR_PLUS, SenderFact.JoinCodeGroup))
        assertEquals(1, dao.getMembers().size)
        assertEquals(setOf(AllowGroup.CODE), groupsOf("00989061234567"))
        assertEquals(MemberSource.LIVE, dao.getMembers().single().source)
        assertFalse(dao.getMembers().single().acknowledged)
    }

    @Test fun anUnreadableSenderGetsNoFact() {
        assertFalse(repo.writeSenderFact("", SenderFact.JoinCodeGroup))
        assertFalse(repo.writeSenderFact("Unknown", SenderFact.AutoAllow(7)))
        assertTrue(dao.getRules().isEmpty())
        assertTrue(dao.getMembers().isEmpty())
    }

    @Test fun aBlockedSenderJoinsNoGroupAndHistoryMembersAreBornAcknowledged() = runTest {
        repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.PREFIX, pattern = "+1555999"))
        assertFalse(repo.joinGroup(AllowGroup.WROTE, SENDER))
        assertFalse(repo.joinGroup(AllowGroup.WROTE, "+15559990000"))
        assertTrue(repo.joinGroup(AllowGroup.WROTE, OTHER, MemberSource.HISTORY))
        val member = repo.groupMembers.first().single()
        assertEquals(AllowGroup.WROTE, member.group)
        assertTrue(member.acknowledged)
    }

    @Test fun removeAndRestoreAMember() = runTest {
        repo.joinGroup(AllowGroup.CODE, SENDER)
        val members = repo.groupMembers.first()
        assertEquals(members, repo.removeGroupMembers(members))
        assertTrue(repo.groupMembers.first().isEmpty())
        repo.restoreGroupMembers(members)
        assertEquals(members, repo.groupMembers.first())
    }

    @Test fun keepAcknowledgesTheMembershipAndTheAutomaticRule() = runTest {
        repo.writeSenderFact(SENDER, SenderFact.AutoAllow(7))
        repo.joinGroup(AllowGroup.CODE, SENDER)
        repo.acknowledgeSender(SENDER)
        assertTrue(rulesFor(SENDER).single().acknowledged)
        assertTrue(dao.getMembers().single().acknowledged)
    }

    /** U5, M-SP3: Block takes away the allow rules and memberships; Undo puts every one of them back. */
    @Test fun blockAndUndoRestoreRulesAndMemberships() = runTest {
        val userAllow = repo.addRule(SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER))
        repo.joinGroup(AllowGroup.CODE, SENDER)
        repo.joinGroup(AllowGroup.WROTE, SENDER)
        val before = repo.groupMembers.first()

        val receipt = repo.blockAddress(SENDER, RuleNotes.BLOCKED_BY_YOU)!!
        assertTrue(receipt.ruleId > 0)
        assertEquals(listOf(userAllow), receipt.removedRules.map { it.id })
        assertEquals(2, receipt.removedMembers.size)
        assertEquals(ListKind.BLOCK, rulesFor(SENDER).single().kind)
        assertTrue(repo.groupMembers.first().isEmpty())

        repo.undoBlock(receipt)
        assertEquals(listOf(userAllow), rulesFor(SENDER).map { it.id })
        assertEquals(before.toSet(), repo.groupMembers.first().toSet())
    }

    @Test fun blockingABlockedSenderAgainAddsNoRuleAndItsUndoKeepsTheOldOne() = runTest {
        val old = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        val receipt = repo.blockAddress(SENDER, RuleNotes.BLOCKED_BY_YOU)!!
        assertEquals(0L, receipt.ruleId)
        repo.undoBlock(receipt)
        assertEquals(listOf(old), rulesFor(SENDER).map { it.id })
        assertNull(repo.blockAddress(" ", RuleNotes.BLOCKED_BY_YOU))
    }

    @Test fun theOldBlockSenderAlsoTakesTheSenderOutOfTheGroups() = runTest {
        repo.joinGroup(AllowGroup.CODE, SENDER)
        val id = repo.storeBlocked(
            BlockedMessage(
                address = SENDER, body = "hello", subscriptionId = 1, isMms = false, receivedAt = 1_000L,
                reason = DecisionReason.UNKNOWN_SENDER,
            )
        )
        repo.blockSender(id)
        assertTrue(groupsOf(SENDER).isEmpty())
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550005678"
        const val IR_NATIONAL = "09061234567"
        const val IR_PLUS = "+989061234567"
    }
}
