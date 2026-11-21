package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.DAY
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.HOUR
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOW
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.SENDER
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second review of step F: a failure of one row is passed over and stuck after three runs while newer rows move
 * (S2-3), a full store or a failed journal write ends the run without a try (S2-3, S2-6), a settle of a deleted MMS
 * is a failed verify, a restored MMS without its id is found (S2-1), SMS and MMS ids are counted apart, and kept
 * pictures are never dropped (S2-2). Every number and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamMoverSecondReviewTest {

    @Test
    fun aFailedWriteOfOneRowIsPassedOverWithoutATryAndTheNextRunMovesIt() {
        val world = OlderSpamWorld()
        // Without a SIM: no second try without one hides the failed write.
        val rows = (0 until 8).map { world.sms(body = "Advert $it", at = NOW - (10 - it) * HOUR, sub = -1) }
        world.failingWrites = 1

        val first = world.mover().run()

        assertThat(first.stalled).isFalse()
        assertThat(first.halted).isFalse()
        assertThat(world.blocked.values.sumOf { it.migrateTries }).isEqualTo(0)
        assertThat(world.store).hasSize(7)
        assertThat(world.journalStalls(rows.first())).isEqualTo(1)
        assertThat(world.journalKind(rows.first())).isNull()

        world.mover().run()

        assertThat(world.store).hasSize(8)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
        assertThat(world.journalStalls(rows.first())).isEqualTo(0)
    }

    @Test
    fun aRowThatAlwaysFailsIsPassedOverNewerRowsMoveAndOnTheThirdDayItIsStuck() {
        val world = OlderSpamWorld()
        val bad = world.sms(body = "Refused every time", at = NOW - 2 * HOUR, sub = -1)
        val good = world.sms(body = "Fine", at = NOW - HOUR, sub = -1)
        world.refusedBodies = setOf("Refused every time")

        world.mover().run()
        assertThat(world.row(good.id).migratedMsgId).isEqualTo(world.store.single().id)
        // T3-2: runs come in bursts; a stall counts once a day.
        repeat(5) { world.mover().run() }
        assertThat(world.journalStalls(bad)).isEqualTo(1)
        world.clock += DAY
        world.mover().run()
        assertThat(world.reports).isEmpty()
        world.clock += DAY
        world.mover().run()

        assertThat(world.journalStalls(bad)).isEqualTo(OlderSpamMover.MAX_STALLS)
        assertThat(world.reports).containsExactly(OlderSpamMover.ROW_STUCK)
        val writes = world.trace.count { it == "writeSms" }
        val after = world.mover().run()
        assertThat(world.trace.count { it == "writeSms" }).isEqualTo(writes)
        assertThat(after.left).isEqualTo(0)
        assertThat(world.row(bad.id).migratedMsgId).isNull()
        assertThat(world.row(bad.id).migrateTries).isEqualTo(0)
        // Never cleaned up, and a new version (a fix) tries it again.
        world.clock += 60 * DAY
        world.mover().cleanUp()
        assertThat(world.blocked).containsKey(bad.id)
        world.refusedBodies = emptySet()
        world.mover(version = 2).run()
        assertThat(world.store).hasSize(2)
    }

    @Test
    fun twoNeighbouringRowsThatAlwaysFailAreSetAsideAfterThreeDaysAndTheRowsAfterThemMove() {
        val world = OlderSpamWorld()
        val first = world.sms(body = "Refused one", at = NOW - 3 * HOUR, sub = -1)
        val second = world.sms(body = "Refused two", at = NOW - 2 * HOUR, sub = -1)
        val good = world.sms(body = "Fine", at = NOW - HOUR, sub = -1)
        world.refusedBodies = setOf("Refused one", "Refused two")

        repeat(3) { day ->
            world.mover().run()
            // A second run the same day counts nothing more.
            if (day < 2) world.mover().run()
            assertThat(world.store).isEmpty()
            world.clock += DAY
        }

        assertThat(world.journalStalls(first)).isEqualTo(OlderSpamMover.MAX_STALLS)
        assertThat(world.journalStalls(second)).isEqualTo(OlderSpamMover.MAX_STALLS)
        assertThat(world.reports).containsExactly(OlderSpamMover.ROW_STUCK, OlderSpamMover.ROW_STUCK)
        world.mover().run()
        assertThat(world.row(good.id).migratedMsgId).isEqualTo(world.store.single().id)
        assertThat(world.row(first.id).migratedMsgId).isNull()
    }

    @Test
    fun whenEveryRowFailsTheRunStopsAtTheSecondRowAndAFullStoreSetsNothingAsideInADay() {
        val world = OlderSpamWorld()
        val rows = (0 until 4).map { world.sms(body = "Advert $it", at = NOW - (10 - it) * HOUR, sub = -1) }
        world.failingWrites = 100

        val result = world.mover().run()
        repeat(5) { world.mover().run() }

        assertThat(result.stalled).isTrue()
        assertThat(result.waiting).isFalse()
        assertThat(rows.map { world.journalStalls(it) }).containsExactly(1, 1, 0, 0).inOrder()
        assertThat(world.reports).isEmpty()
    }

    @Test
    fun aLostRoleCountsNoStall() {
        val world = OlderSpamWorld()
        val rows = (0 until 3).map { world.sms(body = "Advert $it", at = NOW - (10 - it) * HOUR, sub = -1) }
        world.isDefault = false

        world.mover().run()
        world.clock += DAY
        world.mover().run()

        assertThat(rows.map { world.journalStalls(it) }.toSet()).containsExactly(0)
    }

    @Test
    fun aRowMarkedNothingToMoveIsMovedAsAnMmsWhenItsKeptFileCameLater() {
        val world = OlderSpamWorld()
        val row = world.placeholderMms()
        world.mover().run()
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(MigrationMark.NOTHING_TO_MOVE)
        // The late private download (receivers/BlockedMmsDownloadReceiver) recorded its file.
        world.blocked[row.id] = world.row(row.id).copy(media = MmsMedia.STORED, mediaFile = "pdu-late", body = "Late")
        world.identities[row.id] = MmsIdentity("mid-late", "Late".length, pduDate = row.receivedAt / 1000)

        world.mover().run()

        assertThat(world.store.single().mms).isTrue()
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
        assertThat(world.journalKind(row)).isEqualTo(MigrationJournal.KIND_MMS)
    }

    @Test
    fun aMatchBySenderAndSecondThatAnotherRowTookIsNotTakenAndTheRowIsStuckAfterThreeDays() {
        val world = OlderSpamWorld()
        // Two MMS of one sender in one second, without carrier ids.
        val first = world.keptMms(body = "One", messageId = null, at = NOW - HOUR)
        val second = world.keptMms(body = "Two", messageId = null, at = NOW - HOUR + 300)
        world.mover().run()

        assertThat(world.row(first.id).migratedMsgId).isEqualTo(world.store.single().id)
        assertThat(world.row(second.id).migratedMsgId).isNull()
        assertThat(world.journalKind(second)).isNull()
        assertThat(world.journalStalls(second)).isEqualTo(1)
        repeat(2) {
            world.clock += DAY
            world.mover().run()
        }
        assertThat(world.journalStalls(second)).isEqualTo(OlderSpamMover.MAX_STALLS)
        assertThat(world.reports).containsExactly(OlderSpamMover.ROW_STUCK)
        world.clock += 60 * DAY
        world.mover().cleanUp()
        assertThat(world.blocked).containsKey(second.id)
        assertThat(world.store).hasSize(1)
    }

    @Test
    fun anOpenAttemptWhoseRowCannotBeReadIsKeptAndHoldsEveryMmsUntilItIsResolved() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null, at = NOW - 2 * HOUR)
        val other = world.keptMms(address = OlderSpamWorld.OTHER, body = "Other", messageId = null, at = NOW - HOUR)
        world.killAfter = { it == "writeMms" }
        runCatching { world.mover().run() }
        world.restart()
        world.unreadableFiles += row.id

        world.mover().run()

        assertThat(world.journalAttempt()?.rowId).isEqualTo(row.id)
        assertThat(world.row(row.id).migratedMsgId).isNull()
        assertThat(world.row(other.id).migratedMsgId).isNull()
        assertThat(world.store).hasSize(1)

        world.unreadableFiles.clear()
        world.mover().run()

        world.assertAllMovedOnce()
        assertThat(world.store).hasSize(2)
    }

    @Test
    fun aCutOffWriteOfARowWhoseIdWasHandedOutAgainIsNotAdoptedByTheNewRow() {
        val world = OlderSpamWorld()
        val old = world.keptMms(messageId = "mid-old")
        world.killAfter = { it == "writeMms" }
        runCatching { world.mover().run() }
        world.restart()
        // A trip to 0.4.14: the id is handed out again, to another kept MMS.
        world.blocked.clear()
        val again = world.add(old.copy(body = "Another", receivedAt = NOW - 5 * HOUR, mediaFile = "pdu-again"))
        world.identities[again.id] = MmsIdentity("mid-again", "Another".length, pduDate = again.receivedAt / 1000)

        world.mover().run()

        val written = world.store.single { it.messageId == "mid-again" }
        assertThat(world.row(again.id).migratedMsgId).isEqualTo(written.id)
        assertThat(world.journalAttempt()).isNull()
    }

    @Test
    fun aJournalWriteThatFailedEndsTheRunAndTheNextRunWritesNothingTwice() {
        val world = OlderSpamWorld()
        world.mover().run()
        val row = world.sms()
        world.failingJournalWrites = 1

        val first = world.mover().run()

        assertThat(first.stalled).isTrue()
        assertThat(world.row(row.id).migratedMsgId).isNull()

        world.mover().run()

        assertThat(world.store).hasSize(1)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun aSettleOfAnMmsTheUserDeletedMeanwhileIsAFailedVerify() {
        val world = OlderSpamWorld()
        val row = world.keptMms(messageId = null)
        world.killAfter = { it == "markWritten" }
        runCatching { world.mover().run() }
        world.restart()
        world.store.clear()

        val result = world.mover().run()

        assertThat(result.failed).isEqualTo(1)
        assertThat(world.row(row.id).migrateTries).isEqualTo(1)
        assertThat(world.store).isEmpty()
    }

    @Test
    fun aRestoredBackupWithTheMmsWithoutItsIdAndAnOlderOneWithTheHeldRowGiveOneMms() {
        val world = OlderSpamWorld()
        // Fresh install: first a backup made after the move (the MMS as a message, no carrier id in the store), then
        // one made before it (the held entry with its kept PDU and id).
        val row = world.keptMms(messageId = "mid-restored")
        val restored = world.storeMms(SENDER, "Look at this", row.receivedAt / 1000, null)

        world.mover().run()

        assertThat(world.store).containsExactly(restored)
        assertThat(world.row(row.id).migratedMsgId).isEqualTo(restored.id)
        assertThat(world.trace).doesNotContain("writeMms")
    }

    @Test
    fun anSmsAndAnMmsWithTheSameStoreIdAreNotTakenForOneMessage() {
        val world = OlderSpamWorld()
        val sms = world.sms(body = "Text", at = NOW - 2 * HOUR)
        val mms = world.keptMms(messageId = "mid-same", at = NOW - HOUR)

        val result = world.mover().run()

        // Both store tables hand out 1000 first.
        assertThat(world.store.map { it.id }).containsExactly(1000L, 1000L)
        assertThat(result.moved).isEqualTo(2)
        assertThat(world.row(sms.id).migratedMsgId).isEqualTo(1000L)
        assertThat(world.row(mms.id).migratedMsgId).isEqualTo(1000L)
    }

    @Test
    fun aKeptFileThatCannotBeReadIsNeverMovedAsTextAndGoesAsAnMmsOnceReadable() {
        val world = OlderSpamWorld()
        val row = world.keptMms(body = "Has text too", messageId = "mid-later")
        world.unreadableFiles += row.id

        world.mover().run()

        assertThat(world.store).isEmpty()
        assertThat(world.row(row.id).migratedMsgId).isNull()
        assertThat(world.journalStalls(row)).isEqualTo(1)

        world.unreadableFiles.clear()
        world.mover().run()

        assertThat(world.store.single().mms).isTrue()
        assertThat(world.journalKind(row)).isEqualTo(MigrationJournal.KIND_MMS)
    }

    @Test
    fun aDoneRowWithAKeptFileGoesAtTheCleanUpOnlyWhenItWentAsAnMms() {
        val world = OlderSpamWorld()
        val asMms = world.keptMms(messageId = "mid-gone")
        val nothing = world.placeholderMms()
        world.mover().run()
        // A late result came on the "nothing to move" row and was not moved (yet).
        world.blocked[nothing.id] = world.row(nothing.id).copy(media = MmsMedia.STORED, mediaFile = "pdu-nothing")

        world.clock += 31 * DAY
        world.mover().cleanUp()

        assertThat(world.blocked.keys).containsExactly(nothing.id)
        assertThat(world.deletedMedia).containsExactly(asMms.mediaFile)
    }

    @Test
    fun aRowGivenTheIdOfAnOlderRowIsNotReadAsHowTheOlderOneWent() {
        val world = OlderSpamWorld()
        val old = world.sms(body = "Old text")
        world.mover().run()
        assertThat(world.journalKind(old)).isEqualTo(MigrationJournal.KIND_TEXT)
        // After a trip the id is handed out again, to a kept MMS that arrived at another time.
        world.blocked.clear()
        val again = world.add(
            old.copy(
                body = "A picture", isMms = true, receivedAt = NOW - 3 * HOUR, media = MmsMedia.STORED,
                mediaFile = "pdu-again", migratedMsgId = null,
            )
        )
        world.identities[again.id] = MmsIdentity("mid-again", "A picture".length, pduDate = again.receivedAt / 1000)

        world.mover().run()

        assertThat(world.store.count { it.mms }).isEqualTo(1)
        assertThat(world.journalKind(again)).isEqualTo(MigrationJournal.KIND_MMS)
    }

    @Test
    fun theThreadOfAMovedMmsIsReDatedAlsoWhenItsCacheRowFailedOrItsSettleCameOnALaterRun() {
        val world = OlderSpamWorld()
        world.keptMms(messageId = "mid-thread")
        world.failingCache = true

        world.mover().run()

        assertThat(world.repaired.flatten()).containsExactly(OlderSpamWorld.threadOf(SENDER))

        val later = OlderSpamWorld()
        later.keptMms(address = OlderSpamWorld.OTHER, messageId = "mid-later")
        later.failingSettles = 1
        later.mover().run()
        assertThat(later.repaired).isEmpty()
        later.mover().run()
        assertThat(later.repaired.flatten()).containsExactly(OlderSpamWorld.threadOf(OlderSpamWorld.OTHER))
    }
}
