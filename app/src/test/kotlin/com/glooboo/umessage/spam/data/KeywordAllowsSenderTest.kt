package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.NumberNormalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A keyword or regex allow rule that lets a message in also allows its sender, unless the sender is known. */
@RunWith(AndroidJUnit4::class)
class KeywordAllowsSenderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val normalizer = NumberNormalizer { "GB" }
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    private val dao get() = db.spamDao()
    private val sender = "+447121234567"

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
            platform, db, SpamSettingsStore(app), normalizer,
            blockedFromKeywordNote = { "block note" },
            allowedFromKeywordNote = { "allow note" },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun rule(kind: ListKind, type: RuleType, pattern: String) =
        repo.addRule(SpamRule(kind = kind, type = type, pattern = pattern))

    private fun senderRules(kind: ListKind) = dao.getRules().filter {
        it.kind == kind && it.type != RuleType.KEYWORD && it.type != RuleType.REGEX
    }

    private fun strictFilter() = DefaultSpamFilter(
        { repo.currentRules() }, { SpamSettings(mode = UnknownSenderMode.STRICT) },
        object : ContactChecker {
            override fun isContact(address: String) = false
            override fun sentTo(address: String) = ContactLookup.NOT_FOUND
        },
        normalizer,
    )

    private fun message(body: String) = IncomingMessage(address = sender, body = body, subscriptionId = 1)

    @Test fun allowKeywordAlsoAllowsTheSender() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertTrue(repo.allowSenderOfKeywordMatch(sender, ruleId) { true })
        val added = senderRules(ListKind.ALLOW).single()
        assertEquals(RuleType.NUMBER, added.type)
        assertEquals(sender, added.pattern)
        assertEquals("allow note", added.note)
        assertTrue(senderRules(ListKind.BLOCK).isEmpty())
    }

    @Test fun laterMessageWithoutTheKeywordIsAllowed() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        val filter = strictFilter()
        assertEquals(FilterDecision.Allow(DecisionReason.ALLOW_RULE, ruleId), filter.evaluate(message("your parcel")))
        assertEquals(DecisionReason.UNKNOWN_SENDER, filter.evaluate(message("hello")).reason)
        assertTrue(repo.allowSenderOfKeywordMatch(sender, ruleId) { true })
        assertEquals(DecisionReason.ALLOW_RULE, filter.evaluate(message("hello")).reason)
    }

    @Test fun regexAllowOfASenderIdAddsASenderIdRule() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.REGEX, "par+cel")
        assertTrue(repo.allowSenderOfKeywordMatch("vm-shop", ruleId) { true })
        assertEquals(RuleType.SENDER_ID, senderRules(ListKind.ALLOW).single().type)
    }

    @Test fun anExplicitBlockOnTheSenderStaysAndNothingIsAdded() = runTest {
        rule(ListKind.BLOCK, RuleType.NUMBER, sender)
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertFalse(repo.allowSenderOfKeywordMatch(sender, ruleId) { true })
        assertEquals(1, senderRules(ListKind.BLOCK).size)
        assertTrue(senderRules(ListKind.ALLOW).isEmpty())
    }

    @Test fun anExistingAllowMeansNothingToDo() = runTest {
        rule(ListKind.ALLOW, RuleType.NUMBER, sender)
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertFalse(repo.allowSenderOfKeywordMatch(sender, ruleId) { true })
        assertEquals(1, senderRules(ListKind.ALLOW).size)
    }

    @Test fun aKnownSenderIsSkipped() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertFalse(repo.allowSenderOfKeywordMatch(sender, ruleId) { false })
        assertFalse(repo.allowSenderOfKeywordMatch(sender, ruleId) { error("lookup failed") })
        assertTrue(senderRules(ListKind.ALLOW).isEmpty())
    }

    @Test fun aBlankSenderIsSkipped() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        assertFalse(repo.allowSenderOfKeywordMatch("", ruleId) { true })
        assertTrue(senderRules(ListKind.ALLOW).isEmpty())
    }

    @Test fun onlyAKeywordOrRegexAllowRuleTriggersIt() = runTest {
        val block = rule(ListKind.BLOCK, RuleType.KEYWORD, "prize")
        val number = rule(ListKind.ALLOW, RuleType.NUMBER, "+447350000000")
        assertFalse(repo.allowSenderOfKeywordMatch(sender, block) { true })
        assertFalse(repo.allowSenderOfKeywordMatch(sender, number) { true })
        assertFalse(repo.allowSenderOfKeywordMatch(sender, null) { true })
        assertTrue(senderRules(ListKind.BLOCK).isEmpty())
        assertEquals(1, senderRules(ListKind.ALLOW).size)
    }

    @Test fun allowKeywordNeverCreatesABlockRule() = runTest {
        val ruleId = rule(ListKind.ALLOW, RuleType.KEYWORD, "parcel")
        repo.allowSenderOfKeywordMatch(sender, ruleId) { true }
        assertTrue(dao.getRules().none { it.kind == ListKind.BLOCK })
    }
}
