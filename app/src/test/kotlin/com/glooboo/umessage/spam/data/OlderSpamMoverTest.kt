package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.DAY
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.HOUR
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOTIFICATION_IND
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOW
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.OTHER
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.SENDER
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.THIRD
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 8 and the message-migration block of 12.1: every step of the migration of held rows into the phone's store,
 * a kill after each step (also inside mmslib's write), the verify (S2), the MMS probe (S4), cache rows (S7), SIM -1
 * and blank senders (S17), the downgrade trip (S17), not the default app, the stop after too many failed verifies,
 * the clean-up 30 days later, and the review findings F-3 to F-6, F-14, F-15, F-16, F-19, F-21 to F-23. Every number
 * and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamMoverTest {

    private fun OlderSpamWorld.seedMixed() {
        sms(body = "First advert", at = NOW - 5 * HOUR)
        sms(address = OTHER, body = "Second advert", at = NOW - 4 * HOUR, sub = 2)
        keptMms(body = "A picture", at = NOW - 3 * HOUR, messageId = "mid-7")
        keptMms(address = THIRD, body = "No message id", at = NOW - 2 * HOUR, messageId = null)
        textOnlyMms(body = "Text of a lost picture", at = NOW - HOUR)
        placeholderMms(at = NOW - 30 * 60 * 1000)
    }

    @Test
    fun everyKindOfHeldRowIsMovedOnceReadSeenAndDatedWhenItArrived() {
        val world = OlderSpamWorld().apply { seedMixed() }

        val result = world.mover().run()

        world.assertAllMovedOnce()
        assertThat(result.moved).isEqualTo(5)
        assertThat(result.left).isEqualTo(0)
        assertThat(result.waiting).isFalse()
        assertThat(world.store).hasSize(5)
        // The text-only MMS became an SMS; the SIM of each SMS was kept.
        assertThat(world.store.count { !it.mms }).isEqualTo(3)
        assertThat(world.store.first { it.body == "Second advert" }.sub).isEqualTo(2)
    }

    @Test
    fun aKillAfterAnyStepResumesWithoutADuplicateAndCountsMatch() {
        val clean = OlderSpamWorld().apply { seedMixed() }
        clean.mover().run()
        val steps = clean.trace.size
        assertThat(steps).isGreaterThan(20)

        for (step in 1..steps) {
            val world = OlderSpamWorld().apply { seedMixed() }
            var seen = 0
            world.killAfter = { ++seen == step }
            runCatching { world.mover().run() }
            assertThat(world.killed).isTrue()
            world.restart()
            world.mover().run()
            // A second start after the resume changes nothing.
            val storeAfter = world.store.map { it.copy() }
            world.mover().run()

            assertThat(world.store).isEqualTo(storeAfter)
            assertThat(world.store).hasSize(5)
            world.assertAllMovedOnce()
        }
    }

    @Test
    fun aKillInsideMmslibsWriteLeavesAHalfMmsThatIsDeletedAndWrittenOnceWhole() {
        // A plain MMS cut before its first address, and a group MMS (FROM and two TO) cut between its addresses.
        for ((messageId, addresses, killAfter) in listOf(
            Triple("mid-half", 2, 0), Triple(null, 2, 0), Triple("mid-group", 3, 1), Triple(null, 3, 2),
        )) {
            val world = OlderSpamWorld()
            world.keptMms(messageId = messageId, addresses = addresses)
            world.halfWriteKillAfter = killAfter
            runCatching { world.mover().run() }
            assertThat(world.store.single().whole).isFalse()
            world.restart()

            world.mover().run()

            world.assertAllMovedOnce()
            assertThat(world.store).hasSize(1)
            assertThat(world.store.single().addresses).isEqualTo(addresses)
            assertThat(world.trace).contains("deleteMms")
        }
    }

    @Test
    fun severalKillsInARowDuringTheMigrationStillEndWithEveryRowOnce() {
        val world = OlderSpamWorld().apply {
            repeat(120) { sms(body = "Advert $it", at = NOW - (200 - it) * HOUR) }
            keptMms(messageId = "mid-x")
        }
        var ops = 0
        for (killAt in listOf(7, 40, 133, 290, 401)) {
            world.killAfter = { ++ops >= killAt }
            runCatching { world.mover().run() }
            world.restart()
            ops = 0
        }
        world.mover().run()

        world.assertAllMovedOnce()
        assertThat(world.store).hasSize(121)
    }

    @Test
    fun aFailedVerifyRaisesTheTriesAndNeverWritesAgain() {
        val world = OlderSpamWorld()
        val row = world.sms()
        world.failingChecks = 1

        val first = world.mover().run()

        assertThat(first.failed).isEqualTo(1)
        assertThat(world.store).hasSize(1)
        assertThat(world.row(row.id).migrateTries).isEqualTo(1)
        assertThat(MigrationMark.isWritten(world.row(row.id).migratedMsgId)).isTrue()

        world.mover().run()

        assertThat(world.store).hasSize(1)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun aRowWhoseVerifyFailsTwiceIsGivenUpAndItsOldCopyIsKeptForGood() {
        val world = OlderSpamWorld()
        val row = world.sms()
        world.failingChecks = 2

        world.mover().run()
        world.mover().run()
        val third = world.mover().run()

        assertThat(world.store).hasSize(1)
        assertThat(world.row(row.id).migrateTries).isEqualTo(OlderSpamMover.MAX_TRIES)
        assertThat(third.left).isEqualTo(0)
        // Not done: the clean-up never deletes it.
        world.clock += 60 * DAY
        world.mover().cleanUp()
        assertThat(world.blocked).containsKey(row.id)
    }

    @Test
    fun aRoleLostDuringTheRunEndsItAsWaitingWithoutTriesAndNothingIsHalted() {
        val world = OlderSpamWorld()
        repeat(10) { world.sms(body = "Advert $it", at = NOW - (20 - it) * HOUR) }
        world.roleLostAfterWrites = 3

        val result = world.mover().run()

        assertThat(result.waiting).isTrue()
        assertThat(result.halted).isFalse()
        assertThat(world.store).hasSize(3)
        assertThat(world.blocked.values.sumOf { it.migrateTries }).isEqualTo(0)

        world.roleLostAfterWrites = null
        world.isDefault = true
        world.mover().run()
        world.assertAllMovedOnce()
        assertThat(world.store).hasSize(10)
    }

    @Test
    fun aSettleThatFailedIsDoneAgainOnTheNextRunBeforeTheVerify() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null)
        world.failingSettles = 1

        world.mover().run()

        assertThat(MigrationMark.isWritten(world.row(row.id).migratedMsgId)).isTrue()
        assertThat(world.row(row.id).migrateTries).isEqualTo(0)
        assertThat(world.store.single().read).isEqualTo(0)
        assertThat(world.journalStalls(row)).isEqualTo(1)

        world.mover().run()

        world.assertAllMovedOnce()
    }

    @Test
    fun aResumeThatCannotBeCheckedHoldsEveryMmsOfTheRunButLetsTextsGoOn() {
        val world = OlderSpamWorld()
        val first = world.keptMms(messageId = null, at = NOW - 3 * HOUR)
        val second = world.keptMms(address = OTHER, body = "Second", messageId = null, at = NOW - 2 * HOUR)
        world.sms(at = NOW - HOUR)
        world.killAfter = { it == "writeMms" }
        runCatching { world.mover().run() }
        world.restart()
        world.failingResumes = 1

        world.mover().run()

        // The first MMS is not written again, the second waits, the text went.
        assertThat(world.store.count { it.mms }).isEqualTo(1)
        assertThat(world.row(second.id).migratedMsgId).isNull()
        assertThat(world.store.count { !it.mms }).isEqualTo(1)
        assertThat(world.journalAttempt()?.rowId).isEqualTo(first.id)

        world.mover().run()

        world.assertAllMovedOnce()
        assertThat(world.store).hasSize(3)
    }

    @Test
    fun anMmsIsFoundByItsMessageIdAndNotWrittenAgain() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = "mid-42")
        // Already in the store (a downgrade trip): another time than the held row, the same carrier message id.
        val existing = world.storeMms(SENDER, "A picture", row.receivedAt / 1000 + 77, "mid-42")

        world.mover().run()

        assertThat(world.store).containsExactly(existing)
        // Its time differs and uMessage did not write it, so the verify keeps the old copy (never a second write).
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(MigrationMark.written(existing.id))
        assertThat(world.row(row.id).migrateTries).isEqualTo(1)
    }

    @Test
    fun anMmsFoundByItsMessageIdWithItsOwnTimePassesTheVerify() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = "mid-43")
        val existing = world.storeMms(SENDER, "Look at this", row.receivedAt / 1000, "mid-43")

        world.mover().run()

        assertThat(world.store).containsExactly(existing)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(existing.id)
    }

    @Test
    fun anMmsWithoutAMessageIdIsFoundBySenderAndTime() {
        val world = OlderSpamWorld()
        val row = world.keptMms(body = "No id", messageId = null)
        val existing = world.storeMms(SENDER, "No id", row.receivedAt / 1000, null)

        world.mover().run()

        assertThat(world.store).containsExactly(existing)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(existing.id)
    }

    @Test
    fun aTapToDownloadNoticeIsNeverTakenForTheHeldMms() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null)
        world.storeMms(SENDER, "", row.receivedAt / 1000, null, messageType = NOTIFICATION_IND)

        world.mover().run()

        assertThat(world.store).hasSize(2)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single { it.messageType == 132 }.id)
    }

    @Test
    fun twoHeldRowsOfOneCarrierMessageEndWithOneStoreRowAndNoFailedTry() {
        val world = OlderSpamWorld()
        val first = world.keptMms(messageId = "mid-twice", at = NOW - 2 * HOUR)
        val second = world.keptMms(messageId = "mid-twice", at = NOW - HOUR)

        val result = world.mover().run()

        assertThat(world.store).hasSize(1)
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.row(first.id).migratedMsgId).isEqualTo(world.store.single().id)
        assertThat(world.row(second.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun aGroupMmsIsPersistedOnceAndVerifiedByItsSender() {
        val world = OlderSpamWorld()
        // mmslib puts a multi-recipient PDU into the thread of all its people; the migration writes it once.
        val row = world.keptMms(body = "To three of us", messageId = "mid-group")

        world.mover().run()

        assertThat(world.store.filter { it.mms }).hasSize(1)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun anMmsPersistThatFailedButLeftAWholeRowIsTakenNotWrittenTwice() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null)
        world.persistFails = true
        world.leavesPartial = true

        world.mover().run()
        world.persistFails = false
        world.mover().run()

        assertThat(world.store).hasSize(1)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun aLaterMmsOfTheSameSenderIsNotTakenForACutOffWrite() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null)
        world.killAfter = { it == "attempt=" }
        runCatching { world.mover().run() }
        world.restart()
        // The kill came before mmslib wrote. The same sender's real MMS arrives 30 s later (S2-5: no time window)
        // and another hours later; neither carries the held PDU's date.
        val soon = world.storeMms(SENDER, "Another", world.clock / 1000 + 30, null, dateSent = world.clock / 1000 + 20)
        world.clock += 5 * HOUR
        val later = world.storeMms(SENDER, "A new picture", world.clock / 1000, null)

        world.mover().run()

        assertThat(world.store).hasSize(3)
        assertThat(soon.read).isEqualTo(1)
        assertThat(later.date).isEqualTo(world.clock / 1000)
        assertThat(world.row(row.id).migratedMsgId).isNotIn(listOf(soon.id, later.id))
    }

    @Test
    fun howARowWentIsKeptSoALateMmsResultDoesNotChangeItsVerifyAndItsPicturesStay() {
        val world = OlderSpamWorld()
        val row = world.textOnlyMms(body = "Text first", media = MmsMedia.MISSING)
        world.failingChecks = 1
        world.mover().run()
        assertThat(world.journalKind(row)).isEqualTo(MigrationJournal.KIND_TEXT)
        // The private download comes in late: the row now names a kept PDU.
        world.blocked[row.id] = world.row(row.id).copy(media = MmsMedia.STORED, mediaFile = "pdu-late")
        world.identities[row.id] = MmsIdentity("mid-late", "Text first".length)

        world.mover().run()

        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
        assertThat(world.store.single().mms).isFalse()
        // S2-2: its pictures went nowhere, so the clean-up keeps the row and its file.
        world.clock += 60 * DAY
        assertThat(world.mover().cleanUp()).isEqualTo(0)
        assertThat(world.blocked).containsKey(row.id)
        assertThat(world.deletedMedia).isEmpty()
    }

    @Test
    fun eachMovedMessageGetsItsCacheRowAndItsThreadIsRepaired() {
        val world = OlderSpamWorld()
        world.sms(body = "one")
        world.sms(body = "two", at = NOW - 2 * HOUR)
        world.keptMms(messageId = "mid-c")

        world.mover().run()

        assertThat(world.cached.map { it.first }).containsExactlyElementsIn(world.store.map { it.id })
        assertThat(world.cached.single { it.second }.first).isEqualTo(world.store.single { it.mms }.id)
        assertThat(world.repaired.flatten()).containsExactly(OlderSpamWorld.threadOf(SENDER))
    }

    @Test
    fun aRepairThatFailedIsTriedAgainOnTheNextRun() {
        val world = OlderSpamWorld()
        world.sms()
        world.failingRepairs = 1

        world.mover().run()
        assertThat(world.journalPendingRepairs()).containsExactly(OlderSpamWorld.threadOf(SENDER))

        world.mover().run()

        assertThat(world.repaired.flatten()).containsExactly(OlderSpamWorld.threadOf(SENDER))
        assertThat(world.journalPendingRepairs()).isEmpty()
    }

    @Test
    fun rowsAreMovedOldestFirstInBatchesOf50() {
        val world = OlderSpamWorld()
        repeat(120) { world.sms(body = "Advert $it", at = NOW - it * HOUR) }

        world.mover().run()

        assertThat(world.store.map { it.date }).isInOrder()
        // One repair per batch of 50 rows (one sender here).
        assertThat(world.repaired).hasSize(3)
    }

    @Test
    fun aRunFromABroadcastMovesABoundedBatch() {
        val world = OlderSpamWorld()
        repeat(120) { world.sms(body = "Advert $it", at = NOW - it * HOUR) }

        val first = world.mover().run(limit = SpamProviderMigration.RECEIVER_BATCH)

        assertThat(world.store).hasSize(SpamProviderMigration.RECEIVER_BATCH)
        assertThat(first.left).isEqualTo(120 - SpamProviderMigration.RECEIVER_BATCH)
        world.mover().run()
        assertThat(world.store).hasSize(120)
    }

    @Test
    fun aRowWithSimMinusOneIsWrittenWithTheDefaultSimValue() {
        val world = OlderSpamWorld()
        world.sms(sub = -1)
        world.sms(body = "odd sim", sub = -7)

        world.mover().run()

        assertThat(world.store.map { it.sub }).containsExactly(-1, -1)
    }

    @Test
    fun aSimThisPhoneNoLongerKnowsIsWrittenWithoutASimOnceForSmsAndMms() {
        val world = OlderSpamWorld()
        val sms = world.sms(sub = 3)
        val mms = world.keptMms(sub = 3, messageId = "mid-sim")
        world.rejectedSims = setOf(3)

        world.mover().run()

        assertThat(world.store).hasSize(2)
        assertThat(world.store.map { it.sub }).containsExactly(-1, -1)
        assertThat(world.row(sms.id).migratedMsgId).isEqualTo(world.store.single { !it.mms }.id)
        assertThat(world.row(mms.id).migratedMsgId).isEqualTo(world.store.single { it.mms }.id)
    }

    @Test
    fun aBlankOrPlaceholderSenderGoesToTheUnknownThread() {
        val world = OlderSpamWorld()
        world.sms(address = "  ", body = "no sender")
        world.sms(address = "Unknown", body = "placeholder sender")

        world.mover().run()

        assertThat(world.store.map { it.address }).containsExactly("Unknown", "Unknown")
        assertThat(world.blocked.values.all { MigrationMark.isDone(it.migratedMsgId) }).isTrue()
    }

    @Test
    fun aTextCutAt10000CharactersIsMovedCut() {
        val world = OlderSpamWorld()
        val cut = BlockedLimits.limitBody("x".repeat(12_000))
        world.sms(body = cut)

        world.mover().run()

        assertThat(world.store.single().body).isEqualTo(cut)
        assertThat(world.store.single().body.length).isEqualTo(BlockedLimits.MAX_BODY_CHARS)
    }

    @Test
    fun twoIdenticalTextsTenSecondsApartAreTwoMessages() {
        val world = OlderSpamWorld()
        world.sms(body = "Same text", at = NOW - HOUR)
        world.sms(body = "Same text", at = NOW - HOUR + 10_000)

        world.mover().run()

        assertThat(world.store).hasSize(2)
    }

    @Test
    fun aPlaceholderIsNothingToMoveAndAStillDownloadingMmsWaits() {
        val world = OlderSpamWorld()
        val placeholder = world.placeholderMms()
        val downloading = world.placeholderMms(at = NOW - HOUR, media = MmsMedia.DOWNLOADING)
        val lateText = world.textOnlyMms(body = "Late text", at = NOW - 3 * DAY, media = MmsMedia.DOWNLOADING)

        val result = world.mover().run()

        assertThat(world.row(placeholder.id).migratedMsgId).isEqualTo(MigrationMark.NOTHING_TO_MOVE)
        assertThat(world.row(downloading.id).migratedMsgId).isNull()
        assertThat(result.waiting).isFalse()
        assertThat(result.left).isEqualTo(1)
        // A download older than a day is not waited for: its text is moved.
        assertThat(world.store.single().body).isEqualTo("Late text")
        assertThat(world.row(lateText.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun afterADowngradeTripTheRowsAreFoundByTheProbeAndNothingIsDoubled() {
        val world = OlderSpamWorld()
        // 0.4.14 copied the rows back without their marks; their messages are in the store from the first update.
        val a = world.sms(body = "moved before")
        val b = world.keptMms(messageId = "mid-trip")
        world.storeSms(SENDER, "moved before", a.receivedAt)
        world.storeMms(SENDER, "Look at this", b.receivedAt / 1000, "mid-trip")

        world.mover().run()

        assertThat(world.store).hasSize(2)
        assertThat(world.blocked.values.map { it.migratedMsgId }).containsExactlyElementsIn(world.store.map { it.id })
        assertThat(world.trace).doesNotContain("writeSms")
        assertThat(world.trace).doesNotContain("writeMms")
    }

    @Test
    fun whileUMessageIsNotTheDefaultAppNothingIsWrittenThenTheMigrationRunsOnce() {
        val world = OlderSpamWorld().apply { seedMixed() }
        world.isDefault = false

        val waiting = world.mover().run()

        assertThat(waiting.waiting).isTrue()
        assertThat(waiting.left).isEqualTo(6)
        assertThat(world.store).isEmpty()
        assertThat(world.blocked.values.map { it.migratedMsgId }.toSet()).containsExactly(null)

        world.isDefault = true
        world.mover().run()
        world.mover().run()

        world.assertAllMovedOnce()
        assertThat(world.store).hasSize(5)
    }

    @Test
    fun tenStartsAfterAFinishedMigrationLeaveTheStoreAsItIs() {
        val world = OlderSpamWorld().apply { seedMixed() }
        world.mover().run()
        val after = world.store.map { it.copy() }

        repeat(10) { world.mover().run() }

        assertThat(world.store).isEqualTo(after)
        assertThat(world.trace.count { it == "writeSms" || it == "writeMms" }).isEqualTo(5)
    }

    @Test
    fun moreThanFiveFailedVerifiesStopTheMigrationForThisVersionAndAreReportedOnce() {
        val world = OlderSpamWorld()
        repeat(10) { world.sms(body = "Advert $it", at = NOW - (20 - it) * HOUR) }
        world.failingChecks = 100

        val stopped = world.mover(version = 7).run()

        assertThat(stopped.halted).isTrue()
        assertThat(stopped.failed).isEqualTo(OlderSpamMover.MAX_FAILED_ROWS + 1)
        assertThat(world.reports).containsExactly(OlderSpamMover.STOPPED)
        val tries = world.blocked.values.sumOf { it.migrateTries }

        val again = world.mover(version = 7).run()

        assertThat(again.halted).isTrue()
        assertThat(world.blocked.values.sumOf { it.migrateTries }).isEqualTo(tries)
        assertThat(world.reports).hasSize(1)
        // A new version (a fix) tries again; the rows are still there, nothing was lost, nothing doubled.
        world.failingChecks = 0
        world.mover(version = 8).run()
        assertThat(world.store).hasSize(10)
        assertThat(world.blocked.values.all { MigrationMark.isDone(it.migratedMsgId) }).isTrue()
    }

    @Test
    fun theOldCopiesGoThirtyDaysAfterTheLastMoveWithTheirFiles() {
        val world = OlderSpamWorld()
        val moved = world.keptMms(messageId = "mid-old")
        val nothing = world.placeholderMms()
        val waiting = world.placeholderMms(media = MmsMedia.DOWNLOADING)
        world.mover().run()

        world.clock += 29 * DAY
        assertThat(world.mover().cleanUp()).isEqualTo(0)
        world.clock += 2 * DAY
        assertThat(world.mover().cleanUp()).isEqualTo(2)

        assertThat(world.blocked.keys).containsExactly(waiting.id)
        assertThat(world.deletedMedia).containsExactly(moved.mediaFile)
        assertThat(world.blocked).doesNotContainKey(nothing.id)
        assertThat(world.journalKind(moved)).isNull()
    }

    @Test
    fun theThreadsOfAKilledBatchAreRepairedOnTheNextStart() {
        val world = OlderSpamWorld()
        world.sms()
        world.killAfter = { it == "markDone" }
        runCatching { world.mover().run() }
        world.restart()
        assertThat(world.journalPendingRepairs()).isNotEmpty()

        world.mover().run()

        assertThat(world.repaired.flatten()).contains(OlderSpamWorld.threadOf(SENDER))
        assertThat(world.journalPendingRepairs()).isEmpty()
        assertThat(world.store).hasSize(1)
    }

    @Test
    fun aMoveOwesTheHistoryCheckUntilItIsHandled() {
        val world = OlderSpamWorld()
        world.sms()
        val mover = world.mover()

        mover.run()
        assertThat(mover.passOwed()).isTrue()
        // A kill before the pass was asked for: the next start still owes it.
        assertThat(world.mover().passOwed()).isTrue()

        mover.passHandled()
        assertThat(world.journalPassOwed()).isFalse()
    }

    @Test
    fun beforeTheFirstSortUnreadCountsWaitAndAreNotedOnceLater() {
        val world = OlderSpamWorld()
        world.sms(body = "not opened", unread = true)
        world.sms(body = "opened", at = NOW - 2 * HOUR)

        world.mover().run(noteUnreadNow = false)
        assertThat(world.unreadNoted).isEmpty()
        assertThat(world.journalPendingUnread()).hasSize(1)

        world.mover().run()
        world.mover().run()

        assertThat(world.unreadNoted).containsExactly(world.store.single { it.body == "not opened" }.id)
        assertThat(world.journalPendingUnread()).isEmpty()
    }

    @Test
    fun aMovedRowTheUserHadNotOpenedCountsOnceAsUnreadInItsSpamRow() {
        val world = OlderSpamWorld()
        world.sms(body = "not opened", unread = true)
        world.sms(body = "opened", at = NOW - 2 * HOUR)
        world.killAfter = { it == "noteUnread" }
        runCatching { world.mover().run() }
        world.restart()

        world.mover().run()

        assertThat(world.unreadNoted).containsExactly(world.store.single { it.body == "not opened" }.id)
    }

    @Test
    fun nothingToMoveRunsNoStoreReadAndIsNotWaiting() {
        val world = OlderSpamWorld()
        world.isDefault = false

        val result = world.mover().run()

        assertThat(result.waiting).isFalse()
        // The stall counts of a new version are reset; nothing of the store is read or written.
        assertThat(world.trace).containsExactly("stalls-", "stallsVersion=", "all", "unread?", "repairs?").inOrder()
    }
}
