package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SenderFactWriter
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.ui.spam.RuleNotes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-2: the sender order wired to the real store. [DefaultSpamFilter.senderJudge] judges, and
 * [DefaultSpamRepository.writeSenderFact] and [AllowGroups] write the facts in Room. For each text the place equals
 * what the chat list would read afterwards ([com.glooboo.umessage.spam.engine.SenderJudge.verdict]). Every number is
 * made up (555 range; 0906 for the Iranian shapes, unassigned).
 */
@RunWith(AndroidJUnit4::class)
class SenderJudgeRepositoryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private lateinit var filter: DefaultSpamFilter
    private var refusals = 0

    private val noContacts = object : ContactChecker {
        override fun isContact(address: String) = false
    }

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
        val normalizer = NumberNormalizer { "US" }
        repo = DefaultSpamRepository(
            platform, db, SpamSettingsStore(app), normalizer,
            blockedFromKeywordNote = { RuleNotes.BLOCKED_FROM_KEYWORD },
            allowedFromKeywordNote = { RuleNotes.ALLOWED_FROM_KEYWORD },
            simRegions = { listOf("IR") },
        )
        filter = DefaultSpamFilter(
            rules = repo::currentRules,
            settings = repo::currentSettings,
            contacts = noContacts,
            normalizer = normalizer,
            groups = repo.groupMembership,
            simRegions = { listOf("IR") },
        )
    }

    @After fun tearDown() = db.close()

    /** Writes through the repository; refuses the first [refusals] writes. */
    private val writer = SenderFactWriter { address, fact ->
        if (refusals > 0) {
            refusals--
            false
        } else {
            repo.writeSenderFact(address, fact)
        }
    }

    private fun receive(address: String, body: String): Judgement {
        val judgement = filter.senderJudge.judge(IncomingMessage(address, body, 1), writer)
        if (!judgement.failOpen) assertEquals(judgement.verdict, filter.senderJudge.verdict(address))
        return judgement
    }

    private fun word(kind: ListKind, pattern: String) =
        runBlocking { repo.addRule(SpamRule(kind = kind, type = RuleType.KEYWORD, pattern = pattern)) }

    @Test fun aCodeInEveryIranianShapeMakesOneMember() {
        val first = receive(IR_NATIONAL, "کد تایید شما: ۴۸۲۹۱۵")
        assertEquals(DecisionReason.GROUP_MEMBER, first.verdict.reason)
        listOf(IR_NATIONAL, IR_PLUS, IR_00).forEach { shape ->
            assertEquals(DecisionReason.GROUP_MEMBER, receive(shape, "سلام").verdict.reason)
        }
        assertEquals(1, db.spamDao().getMembers().size)
        assertEquals(AllowGroup.CODE, db.spamDao().getMembers().single().group)
    }

    @Test fun aServiceLineAndASenderNameJoinToo() {
        assertEquals(DecisionReason.GROUP_MEMBER, receive(SERVICE, "رمز پویا ۵۶۷۸۹۰").verdict.reason)
        assertEquals(DecisionReason.GROUP_MEMBER, receive("ExampleBank", "Your OTP is 482915").verdict.reason)
        assertEquals(DecisionReason.GROUP_MEMBER, receive("EXAMPLEBANK", "Weekend offer").verdict.reason)
        assertEquals(2, db.spamDao().getMembers().size)
    }

    @Test fun anAllowedWordAndABlockedWordWriteRules() {
        word(ListKind.ALLOW, "parcel")
        word(ListKind.BLOCK, "lottery")
        val allowed = receive(SENDER, "Your parcel is on its way")
        assertEquals(DecisionReason.ALLOW_RULE, allowed.verdict.reason)
        assertEquals(DecisionReason.ALLOW_RULE, receive(SENDER, "hello").verdict.reason)

        // The blocked word wins over the automatic allow and takes it away.
        assertTrue(receive(SENDER, "You won the lottery").inSpam)
        assertTrue(receive(SENDER, "hello").verdict is SenderVerdict.Spam)
        val senderRules = repo.currentRules().filter { it.type == RuleType.NUMBER }
        assertEquals(listOf(ListKind.BLOCK), senderRules.map { it.kind })
    }

    @Test fun aRefusedWriteIsRetriedOnceThenFailsOpen() {
        refusals = 1
        assertEquals(DecisionReason.GROUP_MEMBER, receive(SENDER, "Your verification code is 482915").verdict.reason)
        refusals = 2
        assertTrue(receive(OTHER, "Your verification code is 482915").failOpen)
        assertTrue(filter.senderJudge.verdict(OTHER) is SenderVerdict.Spam)
    }

    @Test fun theReplayWritesNothingTwice() {
        word(ListKind.ALLOW, "parcel")
        repeat(3) { receive(SENDER, "Your parcel is on its way") }
        repeat(3) { receive(OTHER, "Your verification code is 482915") }
        assertEquals(1, repo.currentRules().count { it.type == RuleType.NUMBER })
        assertEquals(1, db.spamDao().getMembers().size)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550005678"
        const val IR_NATIONAL = "09061234567"
        const val IR_PLUS = "+989061234567"
        const val IR_00 = "00989061234567"
        const val SERVICE = "30001234"
    }
}
