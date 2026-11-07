package com.glooboo.umessage.helpers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.RETENTION_CODE_BODY
import com.glooboo.umessage.testing.retentionMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I02, I45: which messages the automatic cleanup may move to the recycle bin. Invented messages only. */
@RunWith(AndroidJUnit4::class)
class MessageRetentionTest {

    private val now = 100 * MessageRetention.DAY_MS
    private val cutoff = MessageRetention.cutoffSeconds(now, 7)
    private val old = (cutoff - 100)
    private val recent = (cutoff + 100)

    private fun ids(messages: List<com.glooboo.umessage.models.Message>) = messages.map { it.id }

    @Test
    fun cutoffIsTheGivenDaysBeforeNowInSeconds() {
        assertThat(cutoff).isEqualTo(93 * 24 * 60 * 60)
    }

    @Test
    fun onlyReadOldCodesAreChosen() {
        val candidates = listOf(
            retentionMessage(1, RETENTION_CODE_BODY, date = old),
            retentionMessage(2, RETENTION_CODE_BODY, date = old, read = false),
            retentionMessage(3, "Lunch tomorrow?", date = old),
            retentionMessage(4, RETENTION_CODE_BODY, date = recent),
            retentionMessage(5, RETENTION_CODE_BODY, date = old, type = 2),
            retentionMessage(6, RETENTION_CODE_BODY, date = old, scheduled = true),
        )

        assertThat(ids(MessageRetention.selectCodes(candidates, cutoff) { false })).containsExactly(1L)
    }

    @Test
    fun aStarredCodeIsKept() {
        val candidates = listOf(
            retentionMessage(1, RETENTION_CODE_BODY, date = old),
            retentionMessage(2, RETENTION_CODE_BODY, date = old),
        )

        val chosen = MessageRetention.selectCodes(candidates, cutoff) { it.id == 2L }

        assertThat(ids(chosen)).containsExactly(1L)
    }

    @Test
    fun aLongTextOrOneWithALinkIsNotTakenForACode() {
        val candidates = listOf(
            retentionMessage(1, RETENTION_CODE_BODY + " " + "x".repeat(400), date = old),
            retentionMessage(2, "Your verification code is 482913. Open https://pay.example.net/x now", date = old),
            retentionMessage(3, "Promo code 2025 gives 20% discount", date = old),
        )

        assertThat(MessageRetention.selectCodes(candidates, cutoff) { false }).isEmpty()
    }

    @Test
    fun oldMessagesKeepUnreadStarredPinnedDraftsAndScheduled() {
        val candidates = listOf(
            retentionMessage(1, date = old),
            retentionMessage(2, date = old, read = false),
            retentionMessage(3, date = old),
            retentionMessage(4, date = old, threadId = 9),
            retentionMessage(5, date = old, type = 3),
            retentionMessage(6, date = old, scheduled = true),
            retentionMessage(7, date = recent),
            retentionMessage(8, date = old, type = 2),
            retentionMessage(9, date = old, mms = true),
        )

        val chosen = MessageRetention.selectOld(candidates, cutoff, pinnedThreads = setOf(9L)) { it.id == 3L }

        assertThat(ids(chosen)).containsExactly(1L, 8L, 9L)
    }

    @Test
    fun theSameCallTwiceGivesTheSameAnswer() {
        val candidates = listOf(retentionMessage(1, RETENTION_CODE_BODY, date = old))

        val first = MessageRetention.selectCodes(candidates, cutoff) { false }
        val second = MessageRetention.selectCodes(candidates, cutoff) { false }

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun noteOrReminderMessagesAreKeptLikeStarredOnes() {
        val candidates = listOf(
            retentionMessage(1, date = old),
            retentionMessage(2, date = old),
            retentionMessage(3, RETENTION_CODE_BODY, date = old),
            retentionMessage(4, RETENTION_CODE_BODY, date = old),
        )
        val kept = { m: com.glooboo.umessage.models.Message -> m.id == 2L || m.id == 4L }

        val oldOnes = MessageRetention.selectOld(candidates, cutoff, emptySet(), kept) { false }
        val codes = MessageRetention.selectCodes(candidates, cutoff, kept) { false }

        assertThat(ids(oldOnes)).containsExactly(1L, 3L)
        assertThat(ids(codes)).containsExactly(3L)
    }
}
