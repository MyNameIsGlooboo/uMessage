package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.HOUR
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOW
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J18: the store of Android 14 takes an insert for a SIM that is not there now and then hides the row from every
 * query. A visible message always wins: such a row is written without a SIM (at once when the SIM is not known to be
 * active, else after the read-back failed), the run is not stopped, and a hidden row is deleted where the store allows
 * it. Every number and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamMoverUnknownSimTest {

    @Test
    fun smsRowsTheStoreHidesAreDeletedAndWrittenWithoutTheSim() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        // The phone says SIM 2 is there, the store hides its rows all the same: the read-back is the safety net.
        world.activeSims = setOf(1, 2)
        // More rows than MAX_FAILED_ROWS: the old code stopped the run at the sixth.
        val rows = (0 until 8).map { world.sms(body = "Advert $it", at = NOW - (10 - it) * HOUR, sub = 2) }

        val result = world.mover().run()

        assertThat(result.halted).isFalse()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.store).hasSize(8)
        assertThat(world.store.map { it.sub }.toSet()).containsExactly(OlderSpamMover.DEFAULT_SIM)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
    }

    @Test
    fun anSmsRowIsWrittenWithoutTheSimAtOnceWhenTheSimIsNotActiveOrTheListCannotBeRead() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.sms(body = "Advert", sub = 2)
        world.sms(body = "Other advert", sub = 1, at = NOW - 2 * HOUR)

        assertThat(world.mover().run().moved).isEqualTo(2)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.trace.count { it == "writeSms" }).isEqualTo(2)
        assertThat(world.store.map { it.sub }).containsExactly(OlderSpamMover.DEFAULT_SIM, 1)

        // The same with a list that cannot be read: no SIM is known to be active, even one that is there.
        val blind = OlderSpamWorld()
        blind.simListUnreadable = true
        blind.sms(body = "Advert", sub = 1)

        assertThat(blind.mover().run().moved).isEqualTo(1)
        assertThat(blind.store.single().sub).isEqualTo(OlderSpamMover.DEFAULT_SIM)
    }

    @Test
    fun anSmsRowOnAPresentSimKeepsItsSim() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.sms(body = "Advert", sub = 1)

        val result = world.mover().run()

        assertThat(result.moved).isEqualTo(1)
        assertThat(world.store.single().sub).isEqualTo(1)
    }

    @Test
    fun whenTheDeleteOfAHiddenSmsIsRefusedTheVisibleCopyIsWrittenAllTheSame() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1, 2)
        world.refusingSmsDeletes = true
        val rows = (0 until 8).map { world.sms(body = "Advert $it", at = NOW - (10 - it) * HOUR, sub = 2) }

        val result = world.mover().run()

        // A hidden copy stays (it may show up as a double if a SIM with that id comes back); the message is visible.
        assertThat(result.halted).isFalse()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.hiddenStore).hasSize(8)
        assertThat(world.store).hasSize(8)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
        assertThat(world.journalAttempt()).isNull()
    }

    @Test
    fun aRoleLostBetweenTheDeleteAndTheSecondWriteEndsTheRunWithNothingHiddenAndNothingCounted() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1, 2)
        world.roleLostAfterWrites = 1
        val row = world.sms(body = "Advert", sub = 2)

        val result = world.mover().run()

        assertThat(result.stalled).isTrue()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.store).isEmpty()
        assertThat(world.row(row.id).migratedMsgId).isNull()
    }

    @Test
    fun aReadBackThatThrowsIsAFailureOfTheRowNotAStopOfTheRun() {
        val world = OlderSpamWorld()
        world.activeSims = setOf(1, 2)
        world.visibleThrows = true
        val bad = world.sms(body = "Advert", at = NOW - 2 * HOUR, sub = 2)
        val fine = world.sms(body = "Fine", at = NOW - HOUR, sub = -1)

        val result = world.mover().run()

        assertThat(result.halted).isFalse()
        assertThat(world.row(bad.id).migratedMsgId).isNull()
        // The row written with the SIM is taken out again: none is left behind.
        assertThat(world.store.map { it.sub }).doesNotContain(2)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.row(fine.id).migratedMsgId).isNotNull()
    }

    @Test
    fun mmsRowsTheStoreHidesAreMadeVisibleInPlaceByAnUpdateOfTheirSim() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1, 2)
        val rows = (0 until 7).map {
            world.keptMms(body = "Look $it", at = NOW - (10 - it) * HOUR, messageId = "mid-$it", sub = 2)
        }

        val result = world.mover().run()

        // The store refuses to delete a hidden MMS but accepts an update by id: no second row is written.
        assertThat(result.halted).isFalse()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.store.filter { it.mms }).hasSize(7)
        assertThat(world.store.map { it.sub }.toSet()).containsExactly(OlderSpamMover.DEFAULT_SIM)
        assertThat(world.trace.count { it == "writeMms" }).isEqualTo(7)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
        assertThat(world.journalAttempt()).isNull()
    }

    @Test
    fun whenTheSimUpdateOfAHiddenMmsIsRefusedAVisibleCopyIsWrittenWithoutTheSim() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1, 2)
        world.refusingMmsUpdates = true
        val rows = (0 until 7).map {
            world.keptMms(body = "Look $it", at = NOW - (10 - it) * HOUR, messageId = "mid-$it", sub = 2)
        }

        val result = world.mover().run()

        // The hidden copies cannot be deleted either: they stay, the visible ones are all there, the run goes on.
        assertThat(result.halted).isFalse()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.store.filter { it.mms }).hasSize(7)
        assertThat(world.store.map { it.sub }.toSet()).containsExactly(OlderSpamMover.DEFAULT_SIM)
        assertThat(world.hiddenStore).hasSize(7)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
        assertThat(world.journalAttempt()).isNull()
    }

    @Test
    fun anMmsRowIsWrittenWithoutTheSimAtOnceWhenTheSimIsNotActiveOrTheListCannotBeRead() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.keptMms(sub = 2)

        assertThat(world.mover().run().moved).isEqualTo(1)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.trace.count { it == "writeMms" }).isEqualTo(1)
        assertThat(world.store.single { it.mms }.sub).isEqualTo(OlderSpamMover.DEFAULT_SIM)

        val blind = OlderSpamWorld()
        blind.simListUnreadable = true
        blind.keptMms(sub = 1)

        assertThat(blind.mover().run().moved).isEqualTo(1)
        assertThat(blind.store.single { it.mms }.sub).isEqualTo(OlderSpamMover.DEFAULT_SIM)
    }

    @Test
    fun anMmsRowOnAPresentSimKeepsItsSim() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.keptMms(sub = 1)

        val result = world.mover().run()

        assertThat(result.moved).isEqualTo(1)
        assertThat(world.store.single { it.mms }.sub).isEqualTo(1)
    }
}
