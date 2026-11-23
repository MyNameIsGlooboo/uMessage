package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.HOUR
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOW
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J18: the store of Android 14 takes an insert for a SIM that is not there now and then hides the row from every
 * query. Such a row is deleted and written again without a SIM; the run is not stopped and nothing hidden is left.
 * Every number and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamMoverUnknownSimTest {

    @Test
    fun smsRowsOfAMissingSimAreWrittenWithoutItWhenTheSimsCannotBeListed() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
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
    fun anSmsRowOfAMissingSimIsWrittenWithoutItAtOnceWhenTheSimsAreKnown() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.sms(body = "Advert", sub = 2)

        val result = world.mover().run()

        assertThat(result.moved).isEqualTo(1)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.trace.count { it == "writeSms" }).isEqualTo(1)
        assertThat(world.store.single().sub).isEqualTo(OlderSpamMover.DEFAULT_SIM)
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
    fun anMmsRowOfAMissingSimIsWrittenWithoutItAndNothingHiddenIsLeft() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        val rows = (0 until 7).map {
            world.keptMms(body = "Look $it", at = NOW - (10 - it) * HOUR, messageId = "mid-$it", sub = 2)
        }

        val result = world.mover().run()

        assertThat(result.halted).isFalse()
        assertThat(result.failed).isEqualTo(0)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.store.filter { it.mms }).hasSize(7)
        assertThat(world.store.map { it.sub }.toSet()).containsExactly(OlderSpamMover.DEFAULT_SIM)
        assertThat(rows.map { world.row(it.id).migratedMsgId }).doesNotContain(null)
        assertThat(world.journalAttempt()).isNull()
    }

    @Test
    fun anMmsRowOfAMissingSimIsWrittenWithoutItAtOnceWhenTheSimsAreKnown() {
        val world = OlderSpamWorld()
        world.hidingSims = setOf(2)
        world.activeSims = setOf(1)
        world.keptMms(sub = 2)

        val result = world.mover().run()

        assertThat(result.moved).isEqualTo(1)
        assertThat(world.hiddenStore).isEmpty()
        assertThat(world.trace.count { it == "writeMms" }).isEqualTo(1)
        assertThat(world.store.single { it.mms }.sub).isEqualTo(OlderSpamMover.DEFAULT_SIM)
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
