package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Seeded picks: stable for one message, varied across messages, three different chips or one of each. */
class ReplyComposerTest {

    private val engine = EngineTestData.fixture()

    private fun replies(text: String, seed: Long) = engine.reply(text, "en", seed)!!.replies

    @Test
    fun seedZeroGivesTheFirstEntriesInFileOrder() {
        assertThat(replies("Are you home?", 0L)).containsExactly("Yes", "No", "Let me check").inOrder()
        assertThat(replies("What time is it?", 0L)).containsExactly("Tomorrow", "Tonight", "In an hour").inOrder()
    }

    @Test
    fun oneSeedAlwaysGivesTheSameChips() {
        for (seed in listOf(1L, 7L, 123456789L, -5L)) {
            assertThat(replies("Are you home?", seed)).isEqualTo(replies("Are you home?", seed))
            assertThat(replies("What time is it?", seed)).isEqualTo(replies("What time is it?", seed))
        }
    }

    @Test
    fun differentSeedsGiveVariety() {
        val yesNo = (1L..50L).map { replies("Are you home?", it) }.toSet()
        val whenChips = (1L..50L).map { replies("What time is it?", it) }.toSet()
        val invite = (1L..50L).map { replies("Shall we go?", it) }.toSet()
        assertThat(yesNo.size).isGreaterThan(1)
        assertThat(whenChips.size).isGreaterThan(1)
        assertThat(invite.size).isGreaterThan(1)
    }

    @Test
    fun yesNoGivesOneFromEachPoolInOrder() {
        val table = EngineTestData.fixtureTable()
        for (seed in 1L..30L) {
            val chips = replies("Are you home?", seed)
            assertThat(chips).hasSize(3)
            assertThat(table.yesNo.yes).contains(chips[0])
            assertThat(table.yesNo.no).contains(chips[1])
            assertThat(table.yesNo.later).contains(chips[2])
        }
    }

    @Test
    fun inviteGivesOneFromEachInvitePool() {
        val invite = EngineTestData.fixtureTable().invite
        for (seed in 1L..30L) {
            val chips = replies("Shall we go?", seed)
            assertThat(invite.yes).contains(chips[0])
            assertThat(invite.no).contains(chips[1])
            assertThat(invite.later).contains(chips[2])
        }
    }

    @Test
    fun whenWhereAndRequestGiveThreeDifferentChips() {
        for (seed in 1L..30L) {
            for (text in listOf("What time is it?", "Where is it?", "Please send it?")) {
                val chips = replies(text, seed)
                assertThat(chips).hasSize(3)
                assertThat(chips).containsNoDuplicates()
            }
        }
    }

    @Test
    fun aThreeEntryPoolIsGivenWhole() {
        val small = EngineTestData.fixtureTable().copy(whenBlock = CueReplies(listOf("when"), listOf("A", "B", "C")))
        val chips = SmartEngine(listOf(small)).reply("When?", "en", 9L)!!.replies
        assertThat(chips).containsExactly("A", "B", "C").inOrder()
    }
}
