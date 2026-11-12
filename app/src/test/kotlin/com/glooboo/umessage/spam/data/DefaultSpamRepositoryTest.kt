package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** QD-03: "Not spam" and "Block" on the Blocked screen, plus the write-ahead store behind them. */
@RunWith(AndroidJUnit4::class)
class DefaultSpamRepositoryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var platform: FakePlatform
    private lateinit var repo: DefaultSpamRepository

    private class FakePlatform : SpamPlatform {
        val delivered = mutableListOf<PendingSmsEntity>()
        val deliveredMms = mutableListOf<BlockedMessage>()
        val notified = mutableListOf<BlockedMessage>()
        val deletedMedia = mutableListOf<String>()
        var sweptKeeping: Set<String>? = null
        var mmsDeliveryWorks = true
        var smsDeliveryWorks = true
        var removeWorks = true
        var alreadyInInbox = false
        val removed = mutableListOf<Pair<Long, Boolean>>()
        private var nextInboxId = 500L
        override fun notifyBlocked(message: BlockedMessage) {
            notified += message
        }

        override fun deliverRestored(pending: PendingSmsEntity): Long? {
            if (!smsDeliveryWorks) return null
            delivered += pending
            return if (alreadyInInbox) 0L else nextInboxId++
        }

        override fun deliverRestoredMms(message: BlockedMessage): Long? {
            if (!mmsDeliveryWorks) return null
            deliveredMms += message
            return nextInboxId++
        }

        override fun removeRestored(id: Long, isMms: Boolean): Boolean {
            if (removeWorks) removed += id to isMms
            return removeWorks
        }

        override fun deleteMedia(names: Collection<String>) {
            deletedMedia += names
        }

        override fun sweepMedia(keep: Set<String>) {
            sweptKeeping = keep
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        platform = FakePlatform()
        repo = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "GB" })
    }

    @After fun tearDown() = db.close()

    private val dao get() = db.spamDao()

    private fun blocked(
        address: String = "+447121234567",
        body: String = "You won a prize",
        isMms: Boolean = false,
        receivedAt: Long = 1_000_000L,
    ) = BlockedMessage(
        address = address, body = body, subscriptionId = 1, isMms = isMms, receivedAt = receivedAt,
        reason = DecisionReason.UNKNOWN_SENDER,
    )

    private fun rules(kind: ListKind) = dao.getRules().filter { it.kind == kind }

    /** The "N restored" counter on the Blocked stats card counts Not spam restores, once each. */
    @Test fun notSpamCountsAsRestoredInTheStats() = runTest {
        val first = repo.storeBlocked(blocked(body = "one"))
        val second = repo.storeBlocked(blocked(body = "two", receivedAt = 9_000_000L))
        assertEquals(0, repo.stats.first().restoredTotal)
        repo.markNotSpam(first)
        repo.markNotSpam(first)
        repo.markNotSpam(second)
        assertEquals(2, repo.stats.first().restoredTotal)
    }

    /** A newly held message is unread (its Blocked tab row is bold) until the sender's thread marks it read. */
    @Test fun heldMessagesStartUnreadAndMarkBlockedReadClearsThem() = runTest {
        val first = repo.storeBlocked(blocked(body = "one"))
        val second = repo.storeBlocked(blocked(body = "two", receivedAt = 9_000_000L))
        assertTrue(dao.getAllBlocked().all { it.unread })

        repo.markBlockedRead(listOf(first))

        assertEquals(listOf(false, true), listOf(first, second).map { dao.getBlocked(it)!!.unread })
        assertEquals(listOf(second), repo.blocked.first().filter { it.unread }.map { it.id })
    }

    /** Undo of a restore puts the message back as it was, unread state included. */
    @Test fun undoRestoreKeepsTheReadState() = runTest {
        val id = repo.storeBlocked(blocked())
        repo.markBlockedRead(listOf(id))
        val receipt = repo.restore(id)
        assertTrue(repo.undoRestore(receipt))
        assertEquals(false, dao.getBlocked(id)!!.unread)
    }

    @Test fun notSpamTappedTwiceDeliversOnce() = runTest {
        val id = repo.storeBlocked(blocked())
        listOf(async { repo.markNotSpam(id) }, async { repo.markNotSpam(id) }).awaitAll()
        repo.markNotSpam(id)

        assertEquals(1, platform.delivered.size)
        assertTrue(dao.getAllBlocked().isEmpty())
    }

    /** T12 / SU-16: restoring one message must not whitelist the sender; "Always allow" is a separate choice. */
    @Test fun notSpamRestoresWithoutAllowingTheSender() = runTest {
        val first = repo.storeBlocked(blocked(body = "one"))
        val second = repo.storeBlocked(blocked(address = "07121234567", body = "two", receivedAt = 2_000_000L))
        repo.markNotSpam(first)
        repo.markNotSpam(second)
        assertTrue(rules(ListKind.ALLOW).isEmpty())
        assertEquals(2, platform.delivered.size)
    }

    @Test fun notSpamWritesTheMessageAheadBeforeDelivering() = runTest {
        val id = repo.storeBlocked(blocked(body = "Dinner at 8?"))
        repo.markNotSpam(id)
        // The real platform deletes the pending row once the inbox has it; this fake does not, so the row shows
        // the message was saved before delivery was attempted.
        val pending = dao.getPending().single()
        assertEquals("Dinner at 8?", pending.body)
        assertEquals(1_000_000L, pending.date)
        assertEquals(pending.id, platform.delivered.single().id)
    }

    /** Without "Always allow" the sender's rules stay as they were, so future texts are filtered as usual. */
    @Test fun notSpamKeepsTheSendersBlockRule() = runTest {
        repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "07121234567"))
        val id = repo.storeBlocked(blocked())
        repo.markNotSpam(id)
        assertEquals(1, rules(ListKind.BLOCK).size)
        assertTrue(rules(ListKind.ALLOW).isEmpty())
    }

    @Test fun notSpamForUndownloadedMmsRestoresNothingAndSaysSo() = runTest {
        val id = repo.storeBlocked(blocked(body = "", isMms = true))
        assertEquals(MMS_PLACEHOLDER_BODY, dao.getBlocked(id)!!.body)
        assertEquals(MmsMedia.MISSING, dao.getBlocked(id)!!.media)
        assertEquals(RestoreOutcome.NOTHING, repo.markNotSpam(id))
        assertTrue(rules(ListKind.ALLOW).isEmpty())
        assertTrue(dao.getPending().isEmpty())
        assertTrue(platform.delivered.isEmpty())
        assertTrue(platform.deliveredMms.isEmpty())
        assertTrue(dao.getAllBlocked().isEmpty())
    }

    @Test fun notSpamOfAnSmsSaysRestoredAndASecondTapFindsNothing() = runTest {
        val id = repo.storeBlocked(blocked())
        assertEquals(RestoreOutcome.RESTORED, repo.markNotSpam(id))
        assertEquals(RestoreOutcome.NOT_FOUND, repo.markNotSpam(id))
    }

    /** SE-29: a blocked MMS whose download was kept goes back with its pictures, not as a text. */
    @Test fun notSpamForAKeptMmsRestoresItWithItsPictures() = runTest {
        val id = repo.storeBlocked(keptMms())
        assertEquals(RestoreOutcome.RESTORED, repo.markNotSpam(id))

        assertEquals(FILE, platform.deliveredMms.single().mediaFile)
        assertEquals(1_000_000L, platform.deliveredMms.single().receivedAt)
        assertTrue("no SMS copy of an MMS", dao.getPending().isEmpty())
        assertTrue(platform.delivered.isEmpty())
        assertTrue(dao.getAllBlocked().isEmpty())
        assertEquals(listOf(FILE), platform.deletedMedia)
        assertEquals(1, repo.stats.first().restoredTotal)
    }

    /** A kept MMS that the inbox refuses stays in Blocked with its pictures, and the outcome says it failed. */
    @Test fun notSpamForAKeptMmsThatCannotBeWrittenKeepsIt() = runTest {
        platform.mmsDeliveryWorks = false
        val id = repo.storeBlocked(keptMms())
        assertEquals(RestoreOutcome.FAILED, repo.markNotSpam(id))

        assertEquals(FILE, dao.getBlocked(id)!!.mediaFile)
        assertTrue(platform.deletedMedia.isEmpty())
        assertEquals(0, repo.stats.first().restoredTotal)

        platform.mmsDeliveryWorks = true
        assertEquals(RestoreOutcome.RESTORED, repo.markNotSpam(id))
    }

    @Test fun notSpamTwiceOnAKeptMmsDeliversItOnce() = runTest {
        val id = repo.storeBlocked(keptMms())
        listOf(async { repo.markNotSpam(id) }, async { repo.markNotSpam(id) }).awaitAll()
        assertEquals(1, platform.deliveredMms.size)
    }

    /** SU-17: an MMS whose pictures were lost but whose text is known gets its text back, and is reported as such. */
    @Test fun notSpamForAnMmsWithTextButNoPicturesRestoresOnlyTheText() = runTest {
        val id = repo.storeBlocked(blocked(body = "See the photo", isMms = true))
        assertEquals(RestoreOutcome.TEXT_ONLY, repo.markNotSpam(id))
        assertEquals("See the photo", dao.getPending().single().body)
        assertTrue(platform.deliveredMms.isEmpty())
    }

    @Test fun aFinishedDownloadKeepsTheMmsAndItsText() = runTest {
        val id = repo.storeBlocked(blocked(body = "", isMms = true).copy(media = MmsMedia.DOWNLOADING))
        assertTrue(repo.attachDownloadedMedia(id, FILE, "Hello with a picture"))

        val row = dao.getBlocked(id)!!.toModel()
        assertEquals(MmsMedia.STORED, row.media)
        assertEquals(FILE, row.mediaFile)
        assertEquals("Hello with a picture", row.body)
        assertTrue(row.hasStoredMedia)
    }

    @Test fun aFailedDownloadMarksThePicturesMissingAndKeepsThePlaceholder() = runTest {
        val id = repo.storeBlocked(blocked(body = "", isMms = true).copy(media = MmsMedia.DOWNLOADING))
        assertTrue(repo.attachDownloadedMedia(id, null, null))
        val row = dao.getBlocked(id)!!.toModel()
        assertEquals(MmsMedia.MISSING, row.media)
        assertEquals(MMS_PLACEHOLDER_BODY, row.body)
        assertEquals(RestoreOutcome.NOTHING, row.expectedRestore)
    }

    @Test fun aDownloadFinishingAfterTheRowIsGoneIsNotAttached() = runTest {
        val id = repo.storeBlocked(blocked(body = "", isMms = true).copy(media = MmsMedia.DOWNLOADING))
        repo.deleteBlocked(listOf(id))
        assertEquals(false, repo.attachDownloadedMedia(id, FILE, "late"))
    }

    @Test fun deletingBlockedMessagesDeletesTheirKeptPictures() = runTest {
        val mms = repo.storeBlocked(keptMms())
        val sms = repo.storeBlocked(blocked(body = "plain"))
        repo.deleteBlocked(listOf(mms, sms))
        assertEquals(listOf(FILE), platform.deletedMedia)
        assertTrue(dao.getAllBlocked().isEmpty())
    }

    @Test fun autoPurgeDeletesKeptPicturesAndSweepsLeftovers() = runTest {
        val old = repo.storeBlocked(keptMms(receivedAt = 1_000L))
        val recent = repo.storeBlocked(keptMms(file = OTHER_FILE, receivedAt = System.currentTimeMillis()))
        repo.purgeExpired()

        assertEquals(null, dao.getBlocked(old))
        assertEquals(listOf(FILE), platform.deletedMedia)
        assertEquals(setOf(OTHER_FILE), platform.sweptKeeping)
        assertEquals(OTHER_FILE, dao.getBlocked(recent)!!.mediaFile)
    }

    @Test fun theSameMmsTwiceKeepsOnlyTheFirstCopysPictures() = runTest {
        val first = repo.storeBlocked(keptMms())
        val second = repo.storeBlocked(keptMms(file = OTHER_FILE, receivedAt = 1_000_500L))
        assertEquals(first, second)
        assertEquals(listOf(OTHER_FILE), platform.deletedMedia)
        assertEquals(FILE, dao.getBlocked(first)!!.mediaFile)
    }

    /** Undo of Not spam takes the SMS out of the inbox and puts the blocked row back exactly as it was. */
    @Test fun undoOfAnSmsRestorePutsItBackInBlockedAsItWas() = runTest {
        val original = blocked(body = "Your code is 4829").copy(ruleId = 7, reason = DecisionReason.BLOCK_RULE)
        val id = repo.storeBlocked(original)
        val before = dao.getBlocked(id)!!.toModel()
        val receipt = repo.restore(id)
        assertEquals(RestoreOutcome.RESTORED, receipt.outcome)
        assertTrue(dao.getAllBlocked().isEmpty())
        assertEquals(1, repo.stats.first().restoredTotal)

        assertTrue(repo.undoRestore(receipt))
        assertEquals(listOf(receipt.inboxId to false), platform.removed)
        assertEquals(before, dao.getBlocked(id)!!.toModel())
        assertEquals(0, repo.stats.first().restoredTotal)
        // Undone once is undone: the same receipt cannot take anything out of the inbox again.
        assertEquals(1, dao.getAllBlocked().size)
    }

    /** The inbox already held the message (delivery wrote no row): undo has nothing to remove and still restores. */
    @Test fun undoWhenTheMessageWasAlreadyInTheInboxPutsTheBlockedRowBack() = runTest {
        platform.alreadyInInbox = true
        val id = repo.storeBlocked(blocked())
        val receipt = repo.restore(id)
        assertEquals(0L, receipt.inboxId)
        assertTrue(repo.undoRestore(receipt))
        assertTrue(platform.removed.isEmpty())
        assertEquals(1, dao.getAllBlocked().size)
    }

    /** The inbox copy could not be removed: the message stays restored (never in neither place). */
    @Test fun undoThatCannotRemoveTheInboxCopyLeavesTheRestoreDone() = runTest {
        val id = repo.storeBlocked(blocked())
        val receipt = repo.restore(id)
        platform.removeWorks = false
        assertEquals(false, repo.undoRestore(receipt))
        assertTrue(dao.getAllBlocked().isEmpty())
        assertEquals(1, repo.stats.first().restoredTotal)
    }

    /** The inbox insert failed and waits in the pending queue: Undo takes it out of the queue instead. */
    @Test fun undoOfARestoreStillInThePendingQueueDropsTheQueuedCopy() = runTest {
        platform.smsDeliveryWorks = false
        val id = repo.storeBlocked(blocked())
        val receipt = repo.restore(id)
        assertEquals(dao.getPending().single().id, receipt.pendingId)

        assertTrue(repo.undoRestore(receipt))
        assertTrue(dao.getPending().isEmpty())
        assertTrue(platform.removed.isEmpty())
        assertEquals(id, dao.getAllBlocked().single().id)
    }

    @Test fun undoOfAnMmsOfWhichNothingWasKeptPutsItBack() = runTest {
        val id = repo.storeBlocked(blocked(body = "", isMms = true))
        val receipt = repo.restore(id)
        assertEquals(RestoreOutcome.NOTHING, receipt.outcome)
        assertTrue(repo.undoRestore(receipt))
        assertEquals(MMS_PLACEHOLDER_BODY, dao.getBlocked(id)!!.body)
    }

    /** A kept MMS keeps its files through the undo window, so Undo can put it back with its pictures. */
    @Test fun undoOfAKeptMmsRestorePutsItBackWithItsPictures() = runTest {
        val id = repo.storeBlocked(keptMms())
        val receipt = repo.restore(id)
        assertTrue(platform.deletedMedia.isEmpty())
        // The sweep (Blocked screen start) must not take the file while Undo may still need it.
        repo.purgeExpired()
        assertTrue(FILE in platform.sweptKeeping!!)

        assertTrue(repo.undoRestore(receipt))
        assertEquals(listOf(receipt.inboxId to true), platform.removed)
        val row = dao.getBlocked(id)!!.toModel()
        assertEquals(FILE, row.mediaFile)
        assertTrue(row.hasStoredMedia)
        // A late finish (the snackbar closing after Undo) must not delete the files the row names again.
        repo.finishRestore(receipt)
        assertTrue(platform.deletedMedia.isEmpty())
    }

    @Test fun finishingAKeptMmsRestoreDeletesItsFiles() = runTest {
        val id = repo.storeBlocked(keptMms())
        val receipt = repo.restore(id)
        repo.finishRestore(receipt)
        assertEquals(listOf(FILE), platform.deletedMedia)
        repo.purgeExpired()
        assertTrue(platform.sweptKeeping!!.isEmpty())
    }

    @Test fun nothingToUndoForAFailedOrMissingRestore() = runTest {
        platform.mmsDeliveryWorks = false
        val id = repo.storeBlocked(keptMms())
        assertEquals(false, repo.undoRestore(repo.restore(id)))
        assertEquals(false, repo.undoRestore(RestoreReceipt(null, RestoreOutcome.NOT_FOUND)))
        assertEquals(1, dao.getAllBlocked().size)
    }

    private fun keptMms(file: String = FILE, receivedAt: Long = 1_000_000L) =
        blocked(body = "", isMms = true, receivedAt = receivedAt).copy(media = MmsMedia.STORED, mediaFile = file)

    private companion object {
        const val FILE = "0f9c2a3e-1111-2222-3333-444455556666"
        const val OTHER_FILE = "aa9c2a3e-1111-2222-3333-444455556666"
    }

    @Test fun blockSenderOfASenderIdGetsASenderIdRule() = runTest {
        val id = repo.storeBlocked(blocked(address = "vm-hdfcbk"))
        repo.blockSender(id)
        val block = rules(ListKind.BLOCK).single()
        assertEquals(RuleType.SENDER_ID, block.type)
        assertEquals("VM-HDFCBK", block.pattern)
    }

    /** SU-18: blocking a sender keeps the messages of theirs already in Blocked. */
    @Test fun blockSenderReplacesAllowRuleAndKeepsTheirMessages() = runTest {
        repo.addRule(SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "+447121234567"))
        val id = repo.storeBlocked(blocked(body = "one"))
        val two = repo.storeBlocked(blocked(address = "0712 123 4567", body = "two", receivedAt = 5_000_000L))
        val other = repo.storeBlocked(blocked(address = "+447350000000", body = "three"))

        repo.blockSender(id)
        repo.blockSender(id)

        assertTrue(rules(ListKind.ALLOW).isEmpty())
        assertEquals(1, rules(ListKind.BLOCK).size)
        assertEquals(setOf(id, two, other), dao.getAllBlocked().map { it.id }.toSet())
    }

    @Test fun storeBlockedIgnoresTheSameMessageTwice() = runTest {
        val first = repo.storeBlocked(blocked())
        val second = repo.storeBlocked(blocked(receivedAt = 1_000_500L))
        assertEquals(first, second)
        assertEquals(1, dao.getAllBlocked().size)
    }

    @Test fun addRuleRejectsUnsafeRegex() = runTest {
        try {
            repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "(a+)+"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(dao.getRules().isEmpty())
    }

    @Test fun addRuleTwiceKeepsOneRule() = runTest {
        val a = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "0712 123 4567"))
        val b = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+447121234567"))
        assertEquals(a, b)
        assertEquals(1, dao.getRules().size)
    }

    // SU-09: editing a rule keeps its id, so blocked messages' reason chips still point at it.
    @Test fun updateRuleKeepsTheIdAndCleansTheValue() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+447121234567", note = "old", createdAt = 5L))
        val result = repo.updateRule(SpamRule(id = id, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "0712 765 4321", note = "new"))
        assertEquals(id, result)
        val rule = dao.getRules().single()
        assertEquals(id, rule.id)
        assertEquals("+447127654321", rule.pattern)
        assertEquals("new", rule.note)
        assertEquals(5L, rule.createdAt)
    }

    @Test fun updateRuleToAnExistingValueChangesNothingAndReturnsThatRule() = runTest {
        val a = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery"))
        val b = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
        val result = repo.updateRule(SpamRule(id = b, kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery"))
        assertEquals(a, result)
        assertEquals(setOf("lottery", "prize"), dao.getRules().map { it.pattern }.toSet())
    }

    @Test fun updateRuleRejectsUnsafeRegexAndKeepsTheOldRule() = runTest {
        val id = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "win\\d+"))
        try {
            repo.updateRule(SpamRule(id = id, kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "(a+)+"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("win\\d+", dao.getRules().single().pattern)
    }

    @Test fun updateRuleOfADeletedRuleFails() = runTest {
        try {
            repo.updateRule(SpamRule(id = 42, kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize"))
            fail("expected NoSuchElementException")
        } catch (_: NoSuchElementException) {
        }
        assertTrue(dao.getRules().isEmpty())
    }

    @Test fun removeRulesThenRestoreRulesBringsBackTheSameIds() = runTest {
        val a = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery"))
        val b = repo.addRule(SpamRule(kind = ListKind.ALLOW, type = RuleType.SENDER_ID, pattern = "MYBANK"))
        val removed = repo.rules.first()
        repo.removeRules(listOf(a, b))
        assertTrue(dao.getRules().isEmpty())
        repo.restoreRules(removed)
        assertEquals(setOf(a, b), dao.getRules().map { it.id }.toSet())
    }

    @Test fun restoreRulesSkipsAValueAddedAgainMeanwhile() = runTest {
        val a = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery"))
        val removed = repo.rules.first()
        repo.removeRules(listOf(a))
        val again = repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery"))
        repo.restoreRules(removed)
        assertEquals(listOf(again), dao.getRules().map { it.id })
    }
}
