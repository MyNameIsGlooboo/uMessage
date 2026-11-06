package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A keyword or regex block rule that blocks a message also blocks its sender, unless the sender is known. */
@RunWith(AndroidJUnit4::class)
class KeywordBlocksSenderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    private val dao get() = db.spamDao()

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
            platform, db, SpamSettingsStore(app), NumberNormalizer { "GB" },
            blockedFromKeywordNote = { "keyword note" },
        )
    }

    @After fun tearDown() = db.close()

    private fun blocked(address: String = "+447121234567", ruleId: Long?) = BlockedMessage(
        address = address, body = "win a prize", subscriptionId = 1, isMms = false, receivedAt = 1_000_000L,
        reason = DecisionReason.BLOCK_RULE, ruleId = ruleId,
    )

    private suspend fun rule(kind: ListKind, type: RuleType, pattern: String) =
        repo.addRule(SpamRule(kind = kind, type = type, pattern = pattern))

    private fun numberBlocks() = dao.getRules().filter {
        it.kind == ListKind.BLOCK && it.type != RuleType.KEYWORD && it.type != RuleType.REGEX
    }

    @Test fun keywordBlockAlsoBlocksTheSender() = runTest {
        val ruleId = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        val id = repo.storeBlocked(blocked(ruleId = ruleId))
        assertTrue(repo.blockSenderOfKeywordMatch(id, ruleId) { true })
        val added = numberBlocks().single()
        assertEquals(RuleType.NUMBER, added.type)
        assertEquals("+447121234567", added.pattern)
        assertEquals("keyword note", added.note)
    }

    @Test fun regexBlockOfASenderIdAddsASenderIdRule() = runTest {
        val ruleId = rule(ListKind.BLOCK, RuleType.REGEX, "pri+ze")
        val id = repo.storeBlocked(blocked(address = "vm-shop", ruleId = ruleId))
        assertTrue(repo.blockSenderOfKeywordMatch(id, ruleId) { true })
        assertEquals(RuleType.SENDER_ID, numberBlocks().single().type)
    }

    @Test fun aKnownSenderIsNotBlocked() = runTest {
        val ruleId = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        val id = repo.storeBlocked(blocked(ruleId = ruleId))
        assertFalse(repo.blockSenderOfKeywordMatch(id, ruleId) { false })
        assertTrue(numberBlocks().isEmpty())
    }

    @Test fun storingAloneAddsNoSenderRule() = runTest {
        val ruleId = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        repo.storeBlocked(blocked(ruleId = ruleId))
        assertTrue(numberBlocks().isEmpty())
    }

    @Test fun noDuplicateRuleAndAllowRulesAreRemoved() = runTest {
        rule(ListKind.ALLOW, RuleType.NUMBER, "+447121234567")
        val ruleId = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        val id = repo.storeBlocked(blocked(ruleId = ruleId))
        assertTrue(repo.blockSenderOfKeywordMatch(id, ruleId) { true })
        assertFalse(repo.blockSenderOfKeywordMatch(id, ruleId) { true })
        assertEquals(1, numberBlocks().size)
        assertTrue(dao.getRules().none { it.kind == ListKind.ALLOW })
    }

    @Test fun allowKeywordRuleAndOtherRuleTypesDoNotBlockTheSender() = runTest {
        val allow = rule(ListKind.ALLOW, RuleType.KEYWORD, "prize")
        val number = rule(ListKind.BLOCK, RuleType.NUMBER, "+447350000000")
        val id = repo.storeBlocked(blocked(ruleId = allow))
        assertFalse(repo.blockSenderOfKeywordMatch(id, allow) { true })
        assertFalse(repo.blockSenderOfKeywordMatch(id, number) { true })
        assertFalse(repo.blockSenderOfKeywordMatch(id, null) { true })
        assertEquals(1, numberBlocks().size)
    }

    @Test fun aFailingCheckNeverThrows() = runTest {
        val ruleId = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        val id = repo.storeBlocked(blocked(ruleId = ruleId))
        assertFalse(repo.blockSenderOfKeywordMatch(id, ruleId) { error("lookup failed") })
        assertTrue(numberBlocks().isEmpty())
    }
}
