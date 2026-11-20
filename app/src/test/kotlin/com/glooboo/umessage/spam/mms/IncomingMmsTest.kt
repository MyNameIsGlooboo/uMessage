package com.glooboo.umessage.spam.mms

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderFact
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, 6.2 (step E): where a downloaded MMS is shown, and how a Spam notice gets its chat. R4 (group MMS
 * reaches the group), R1 (Spam row first, safety check after the silent write), fail open on every error.
 */
@RunWith(AndroidJUnit4::class)
class IncomingMmsTest {

    private val store = FakeMmsSpamStore()
    private val mms = IncomingMms(store) { NOW }
    private val row = Uri.parse("content://mms/7")
    private val notice = Uri.parse("content://mms/inbox/42")

    private var judged = 0
    private var probed = 0

    private fun place(
        isGroup: Boolean = false,
        probe: FilterDecision? = FilterDecision.Allow(DecisionReason.CONTACT),
        judgement: Judgement? = spam(SpamCause.UNKNOWN),
        historyChecked: Boolean = true,
    ): MmsPlace = mms.placeDownloaded(
        row = row,
        threadId = THREAD,
        sender = SENDER,
        isGroup = isGroup,
        probe = { probed++; probe },
        judge = { judged++; judgement },
        historyChecked = { historyChecked },
    )

    private fun spam(cause: SpamCause, ruleId: Long? = null, fact: SenderFact? = null) = Judgement(
        verdict = SenderVerdict.Spam(
            cause,
            if (cause == SpamCause.BLOCKED) DecisionReason.BLOCK_RULE else DecisionReason.UNKNOWN_SENDER,
            ruleId,
        ),
        fact = fact,
        factWritten = fact != null,
    )

    // R4: group MMS

    @Test
    fun aGroupMmsFromAnUnknownMember_staysInTheGroupAndIsAnnounced() {
        val place = place(isGroup = true, probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))

        assertThat(place).isEqualTo(MmsPlace.Messages(DecisionReason.UNKNOWN_SENDER))
        // No text check, no fact, no Spam row, and the row stays unread.
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aGroupMmsFromABlockedMember_staysInTheGroupWithoutANotification() {
        val place = place(isGroup = true, probe = FilterDecision.Block(DecisionReason.BLOCK_RULE, 5L))

        assertThat(place).isEqualTo(MmsPlace.QuietGroup)
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        // Silent but not read: it is in Messages, so it keeps its unread mark (R1: never read and silent there).
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.ruleHits).isEmpty()
    }

    @Test
    fun aGroupMmsWhoseSenderCannotBeJudged_isAnnounced() {
        assertThat(place(isGroup = true, probe = null)).isEqualTo(MmsPlace.Messages(null))
    }

    @Test
    fun theEmptyOneToOneChatTheNoticeWasShownIn_isForgottenWhenTheMmsWentToTheGroup() {
        mms.forgetNoticeChat(noticeThread = 55L, landedIn = 66L)

        assertThat(store.forgotten).containsExactly(55L)
    }

    @Test
    fun theNoticeChatIsKept_whenItHasMessagesIsTheSameChatOrCannotBeRead() {
        store.threadsWithMessages += 55L
        store.unreadable += 56L

        mms.forgetNoticeChat(noticeThread = 55L, landedIn = 66L)
        mms.forgetNoticeChat(noticeThread = 56L, landedIn = 66L)
        mms.forgetNoticeChat(noticeThread = 66L, landedIn = 66L)
        mms.forgetNoticeChat(noticeThread = null, landedIn = 66L)
        mms.forgetNoticeChat(noticeThread = 0L, landedIn = 66L)

        assertThat(store.forgotten).isEmpty()
    }

    // One-to-one MMS: judged like an SMS

    @Test
    fun aSpamMms_isReadAndSilentInItsSpamChat_spamRowFirst() {
        val place = place(judgement = spam(SpamCause.UNKNOWN))

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.UNKNOWN))
        assertThat(store.rowSilent[row]).isTrue()
        assertThat(store.spamRows[THREAD]).isEqualTo(Triple(SENDER, SpamCause.UNKNOWN, 1))
        // S9: the Spam row is written before the row is marked read and silent, and checked after (R1).
        assertThat(store.calls).containsExactly("noteSpamArrival", "setSilent(true)", "isSpamThreadOf").inOrder()
        assertThat(store.spamEvents).isEqualTo(1)
        assertThat(probed).isEqualTo(0)
    }

    @Test
    fun aBlockedSendersMms_isInSpamAndCountsForItsRule() {
        val place = place(judgement = spam(SpamCause.BLOCKED, ruleId = 7L))

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.ruleHits).containsExactly(7L)
    }

    @Test
    fun aBlockedWordInTheText_countsForTheWordRule() {
        val place = place(judgement = spam(SpamCause.BLOCKED, ruleId = 9L, fact = SenderFact.AutoBlock(9L)))

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.ruleHits).containsExactly(9L)
    }

    @Test
    fun anAllowedWordInTheText_countsForTheWordRuleAndGoesToMessages() {
        val judgement = Judgement(
            SenderVerdict.Messages(DecisionReason.ALLOW_RULE, 3L), SenderFact.AutoAllow(3L), factWritten = true,
        )

        assertThat(place(judgement = judgement)).isEqualTo(MmsPlace.Messages(DecisionReason.ALLOW_RULE))
        assertThat(store.ruleHits).containsExactly(3L)
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun aMessagesVerdict_leavesTheRowUnreadAndAnnouncesItWithItsReason() {
        val place = place(judgement = Judgement(SenderVerdict.Messages(DecisionReason.CONTACT)))

        assertThat(place).isEqualTo(MmsPlace.Messages(DecisionReason.CONTACT))
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aFactThatCouldNotBeWritten_failsOpenToMessages() {
        val judgement = Judgement(SenderVerdict.Messages(DecisionReason.FACT_FAILED), SenderFact.JoinCodeGroup)

        assertThat(place(judgement = judgement)).isEqualTo(MmsPlace.Messages(DecisionReason.FACT_FAILED))
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aSpamRowThatCannotBeWritten_failsOpenToMessagesUnread() {
        store.failSpamRow = true

        assertThat(place()).isEqualTo(MmsPlace.Messages(null))
        assertThat(store.rowSilent[row]).isFalse()
    }

    @Test
    fun aSpamRowThatIsNotThereAfterTheWrite_failsTheSafetyCheckAndIsAnnouncedUnread() {
        store.spamRowVanishes = true

        assertThat(place()).isEqualTo(MmsPlace.Messages(null))
        // It was marked read and silent, then the safety check (R1) made it unread again.
        assertThat(store.calls).containsExactly(
            "noteSpamArrival", "setSilent(true)", "isSpamThreadOf", "setSilent(false)",
        ).inOrder()
        assertThat(store.rowSilent[row]).isFalse()
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun aFilterThatFailed_failsOpenToMessages() {
        assertThat(place(judgement = null)).isEqualTo(MmsPlace.Messages(null))
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aHistoryCheckThatFailed_failsOpenToMessagesWithoutJudging() {
        assertThat(place(historyChecked = false)).isEqualTo(MmsPlace.Messages(null))
        assertThat(judged).isEqualTo(0)
        assertThat(store.calls).isEmpty()
    }

    // Notices

    @Test
    fun aSpamNotice_getsItsSpamChatReadAndSeen_spamRowFirst() {
        val inSpam = mms.revealNotice(notice, SENDER, SpamCause.BLOCKED)

        assertThat(inSpam).isTrue()
        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[notice]).isEqualTo(thread)
        assertThat(store.rowSilent[notice]).isTrue()
        assertThat(store.spamRows[thread]?.second).isEqualTo(SpamCause.BLOCKED)
        assertThat(store.calls)
            .containsExactly("threadOf", "noteSpamArrival", "isSpamThreadOf", "setThread(silent=true)").inOrder()
    }

    @Test
    fun aSpamNoticeWhoseSpamRowFailed_showsInMessagesUnread() {
        store.failSpamRow = true

        assertThat(mms.revealNotice(notice, SENDER, SpamCause.UNKNOWN)).isFalse()

        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[notice]).isNull()
    }

    @Test
    fun aMessagesNotice_getsItsChatUnread_andANoticeWithoutSenderIsLeftAlone() {
        assertThat(mms.revealNotice(notice, SENDER, null)).isFalse()
        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.spamRows).isEmpty()

        assertThat(mms.revealNotice(Uri.parse("content://mms/inbox/43"), " ", SpamCause.UNKNOWN)).isFalse()
        assertThat(store.threads).hasSize(1)
    }

    @Test
    fun theCauseOfABlock_isBlockedForARuleAndUnknownOtherwise() {
        assertThat(IncomingMms.causeOf(FilterDecision.Block(DecisionReason.BLOCK_RULE, 1L)))
            .isEqualTo(SpamCause.BLOCKED)
        assertThat(IncomingMms.causeOf(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)))
            .isEqualTo(SpamCause.UNKNOWN)
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 12L
        const val NOW = 1_700_000_000_000L
    }
}
