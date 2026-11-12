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

/** Schema 8 data behavior: rule hit counters (spam-23), the approved flag of restored SMS (spam-2), DOMAIN rules. */
@RunWith(AndroidJUnit4::class)
class SpamRuleStatsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    private val dao get() = db.spamDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        val platform = object : SpamPlatform {
            override fun notifyBlocked(message: BlockedMessage) = Unit
            // The inbox insert "fails", so the restore leaves its pending row behind to look at.
            override fun deliverRestored(pending: PendingSmsEntity): Long? = null
            override fun deliverRestoredMms(message: BlockedMessage): Long? = null
            override fun removeRestored(id: Long, isMms: Boolean) = false
            override fun deleteMedia(names: Collection<String>) = Unit
            override fun sweepMedia(keep: Set<String>) = Unit
        }
        repo = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "GB" })
    }

    @After fun tearDown() = db.close()

    private fun blocked(
        ruleId: Long?,
        reason: DecisionReason = DecisionReason.BLOCK_RULE,
        body: String = "win a prize",
    ) =
        BlockedMessage(
            address = "+447121234567", body = body, subscriptionId = 1, isMms = false,
            receivedAt = 1_000_000L, reason = reason, ruleId = ruleId,
        )

    private fun stored(id: Long) = dao.getRule(id)!!.toRule()

    @Test fun aNewRuleHasNoHits() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        assertEquals(0, stored(id).hitCount)
        assertEquals(0L, stored(id).lastHit)
    }

    @Test fun aBlockedMessageCountsAHitOnItsRule() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        val before = System.currentTimeMillis()
        repo.storeBlocked(blocked(id, body = "first"))
        repo.storeBlocked(blocked(id, body = "second"))
        val rule = stored(id)
        assertEquals(2, rule.hitCount)
        assertTrue(rule.lastHit >= before)
        // The model the screens read carries the counters too.
        assertEquals(2, repo.currentRules().single().hitCount)
    }

    @Test fun aDuplicateCopyIsNotANewHit() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        repo.storeBlocked(blocked(id))
        repo.storeBlocked(blocked(id))
        assertEquals(1, stored(id).hitCount)
    }

    @Test fun onlyRuleBlocksCountAndOnlyOnTheirOwnRule() = runTest {
        val a = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        val b = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "gift"))
        repo.storeBlocked(blocked(null, DecisionReason.UNKNOWN_SENDER, body = "unknown"))
        repo.storeBlocked(blocked(a))
        repo.storeBlocked(blocked(9_999L, body = "rule that is gone"))
        assertEquals(1, stored(a).hitCount)
        assertEquals(0, stored(b).hitCount)
    }

    @Test fun editingARuleKeepsItsCounters() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        repo.storeBlocked(blocked(id))
        val stale = stored(id).copy(hitCount = 0, lastHit = 0L, pattern = "prizes", note = "edited")
        repo.updateRule(stale)
        val rule = stored(id)
        assertEquals("prizes", rule.pattern)
        assertEquals(1, rule.hitCount)
        assertTrue(rule.lastHit > 0L)
    }

    @Test fun aRestoredRuleKeepsItsCounters() = runTest {
        repo.restoreRules(listOf(SpamRule(7, ListKind.BLOCK, RuleType.KEYWORD, "prize", hitCount = 4, lastHit = 55L)))
        assertEquals(4, stored(7).hitCount)
        assertEquals(55L, stored(7).lastHit)
    }

    @Test fun aRestoredSmsIsQueuedAsApproved() = runTest {
        val id = repo.storeBlocked(blocked(null, DecisionReason.UNKNOWN_SENDER))
        repo.restore(id)
        val queued = dao.getPending().single()
        assertTrue(queued.approved)
        assertEquals("win a prize", queued.body)
    }

    @Test fun anIncomingSmsIsNotApprovedByDefault() {
        val id = dao.insertPending(
            PendingSmsEntity(
                address = "Shop", subject = "", body = "hi", date = 1L, subscriptionId = 1, status = 0, createdAt = 1L,
            )
        )
        assertFalse(dao.getPending().single { it.id == id }.approved)
    }

    @Test fun aDomainRuleIsStoredCleanedAndNotDuplicated() = runTest {
        val first = repo.addRule(
            SpamRule(kind = ListKind.BLOCK, type = RuleType.DOMAIN, pattern = "https://Example.COM/path"),
        )
        val again = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.DOMAIN, pattern = "*.example.com"))
        assertEquals(first, again)
        val rule = stored(first)
        assertEquals(RuleType.DOMAIN, rule.type)
        assertEquals("example.com", rule.pattern)
    }
}
