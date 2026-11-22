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
 * reaches the group), R1 (Spam row first, safety check after the silent write), fail open on every error, and one
 * decision-log row per MMS (E-13).
 */
@RunWith(AndroidJUnit4::class)
class IncomingMmsTest {

    private val store = FakeMmsSpamStore()
    private val mms = IncomingMms(store) { NOW }
    private val row = Uri.parse("content://mms/7")
    private val notice = Uri.parse("content://mms/inbox/42")

    /** A sender check with set answers; records what was asked and logged. */
    private class Check(
        val answer: FilterDecision? = FilterDecision.Allow(DecisionReason.CONTACT),
        val judgement: Judgement? = null,
        val history: Boolean = true,
    ) : MmsSenderCheck {
        var probed = 0
        var judged = 0
        val logged = mutableListOf<Triple<String, String, Long?>>()

        override fun probe(): FilterDecision? = answer.also { probed++ }
        override fun judge(): Judgement? = judgement.also { judged++ }
        override fun historyChecked(): Boolean = history
        override fun log(decision: String, reason: String, ruleId: Long?) {
            logged += Triple(decision, reason, ruleId)
        }
    }

    private fun place(check: Check, others: Int? = 1, counted: Boolean = false): MmsPlace = mms.placeDownloaded(
        row = row, threadId = THREAD, sender = SENDER, recipients = others, check = check, noticeCounted = counted,
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
    fun aGroupMmsFromAnUnknownMember_staysInTheGroupIsAnnouncedAndLogsOneDeliverRow() {
        val check = Check(answer = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))

        val place = place(check, others = 2)

        assertThat(place).isEqualTo(MmsPlace.Messages(DecisionReason.UNKNOWN_SENDER))
        // No text check, no fact, no Spam row, the row stays unread, and the log says it was delivered (E-13).
        assertThat(check.judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowSilent).isEmpty()
        assertThat(check.logged).containsExactly(Triple("DELIVER", "UNKNOWN_SENDER", null))
    }

    @Test
    fun aGroupMmsFromABlockedMember_staysInTheGroupWithoutANotification() {
        val check = Check(answer = FilterDecision.Block(DecisionReason.BLOCK_RULE, 5L))

        val place = place(check, others = 3)

        assertThat(place).isEqualTo(MmsPlace.QuietGroup)
        assertThat(check.judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        // Silent but not read: it is in Messages, so it keeps its unread mark (R1: never read and silent there).
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.ruleHits).isEmpty()
        assertThat(check.logged).containsExactly(Triple("DELIVER", "BLOCK_RULE", 5L))
    }

    @Test
    fun e10_participantsThatCannotBeRead_countAsAGroup_neverReadAndSilent() {
        val unknown = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)
        val check = Check(answer = unknown, judgement = spam(SpamCause.UNKNOWN))

        assertThat(place(check, others = null)).isEqualTo(MmsPlace.Messages(DecisionReason.UNKNOWN_SENDER))
        assertThat(place(check, others = 0)).isEqualTo(MmsPlace.Messages(DecisionReason.UNKNOWN_SENDER))
        assertThat(check.judged).isEqualTo(0)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aGroupMmsWhoseSenderCannotBeJudged_isAnnounced() {
        assertThat(place(Check(answer = null), others = 2)).isEqualTo(MmsPlace.Messages(null))
    }

    @Test
    fun e15_recipientsAreCountedAsTheListsCountThem_andOneUnreadableMakesItUnknown() {
        // The user's own number counts too, as in the cached chat (is_group_conversation).
        assertThat(IncomingMms.recipients(listOf(SENDER, "+15550009999"))).isEqualTo(2)
        assertThat(IncomingMms.recipients(listOf(SENDER))).isEqualTo(1)
        // E-10: one blank recipient is enough to not know.
        assertThat(IncomingMms.recipients(listOf(SENDER, " "))).isNull()
        assertThat(IncomingMms.recipients(emptyList())).isNull()
    }

    @Test
    fun e15_aSpamMmsWhoseChatTheListsDoNotShowInSpam_failsTheSafetyCheck_unreadAndAnnounced() {
        store.notListed += THREAD

        assertThat(place(Check(judgement = spam(SpamCause.UNKNOWN))))
            .isEqualTo(MmsPlace.Messages(null, forwards = false))
        assertThat(store.rowSilent[row]).isFalse()
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun theEmptyOneToOneChatTheNoticeWasShownIn_isForgottenWhenTheMmsWentToTheGroup() {
        mms.forgetNoticeChat(noticeThread = 55L, landedIn = 66L)

        assertThat(store.forgotten).containsExactly(55L)
    }

    @Test
    fun theNoticeChatIsKept_whenItHasMessagesOrAScheduledOneIsTheSameChatOrCannotBeRead() {
        store.threadsWithMessages += 55L
        store.unreadable += 56L
        store.scheduled += 57L

        listOf(55L, 56L, 57L, 66L, 0L, FakeMmsSpamStore.NO_CHAT).forEach {
            mms.forgetNoticeChat(noticeThread = it, landedIn = 66L)
        }
        mms.forgetNoticeChat(noticeThread = null, landedIn = 66L)

        assertThat(store.forgotten).isEmpty()
    }

    @Test
    fun e1_mmslibsDummyThreadIsNoChat() {
        assertThat(IncomingMms.hasChat(FakeMmsSpamStore.NO_CHAT)).isFalse()
        assertThat(IncomingMms.hasChat(0L)).isFalse()
        assertThat(IncomingMms.hasChat(-1L)).isFalse()
        assertThat(IncomingMms.hasChat(null)).isFalse()
        assertThat(IncomingMms.hasChat(12L)).isTrue()
    }

    // One-to-one MMS: judged like an SMS

    @Test
    fun aSpamMms_isReadAndSilentInItsSpamChat_spamRowFirst() {
        val check = Check(judgement = spam(SpamCause.UNKNOWN))

        assertThat(place(check)).isEqualTo(MmsPlace.Spam(SpamCause.UNKNOWN))
        assertThat(store.rowSilent[row]).isTrue()
        assertThat(store.spamRows[THREAD]).isEqualTo(Triple(SENDER, SpamCause.UNKNOWN, 1))
        // S9: the Spam row is written before the row is marked read and silent, and checked after (R1).
        assertThat(store.calls).containsExactly("noteSpamArrival", "setSilent(true)", "isListedInSpam").inOrder()
        assertThat(store.spamEvents).isEqualTo(1)
        // The judgement wrote the one row; nothing else is logged and the sender is not probed.
        assertThat(check.logged).isEmpty()
        assertThat(check.probed).isEqualTo(0)
    }

    @Test
    fun aBlockedSendersMms_isInSpamAndCountsForItsRule() {
        val place = place(Check(judgement = spam(SpamCause.BLOCKED, ruleId = 7L)))

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.ruleHits).containsExactly(7L)
    }

    @Test
    fun aBlockedWordInTheText_countsForTheWordRule() {
        val judgement = spam(SpamCause.BLOCKED, ruleId = 9L, fact = SenderFact.AutoBlock(9L))

        assertThat(place(Check(judgement = judgement))).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.ruleHits).containsExactly(9L)
    }

    @Test
    fun anAllowedWordInTheText_countsForTheWordRuleAndGoesToMessages() {
        val judgement = Judgement(
            SenderVerdict.Messages(DecisionReason.ALLOW_RULE, 3L), SenderFact.AutoAllow(3L), factWritten = true,
        )

        assertThat(place(Check(judgement = judgement))).isEqualTo(MmsPlace.Messages(DecisionReason.ALLOW_RULE))
        assertThat(store.ruleHits).containsExactly(3L)
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun aMessagesVerdict_leavesTheRowUnreadAndAnnouncesItWithItsReason() {
        val place = place(Check(judgement = Judgement(SenderVerdict.Messages(DecisionReason.CONTACT))))

        assertThat(place).isEqualTo(MmsPlace.Messages(DecisionReason.CONTACT))
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aFactThatCouldNotBeWritten_failsOpenToMessages() {
        val judgement = Judgement(SenderVerdict.Messages(DecisionReason.FACT_FAILED), SenderFact.JoinCodeGroup)

        assertThat(place(Check(judgement = judgement))).isEqualTo(MmsPlace.Messages(DecisionReason.FACT_FAILED))
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aSpamRowThatCannotBeWritten_failsOpenToMessagesUnread() {
        store.failSpamRow = true

        assertThat(place(Check(judgement = spam(SpamCause.UNKNOWN))))
            .isEqualTo(MmsPlace.Messages(null, forwards = false))
        assertThat(store.rowSilent[row]).isFalse()
    }

    @Test
    fun aSpamRowThatIsNotThereAfterTheWrite_failsTheSafetyCheckAndIsAnnouncedUnread() {
        store.spamRowVanishes = true

        assertThat(place(Check(judgement = spam(SpamCause.UNKNOWN))))
            .isEqualTo(MmsPlace.Messages(null, forwards = false))
        // It was marked read and silent, then the safety check (R1) made it unread again.
        assertThat(store.calls).containsExactly(
            "noteSpamArrival", "setSilent(true)", "isListedInSpam", "setSilent(false)",
        ).inOrder()
        assertThat(store.rowSilent[row]).isFalse()
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun aFilterThatFailed_failsOpenToMessages() {
        assertThat(place(Check(judgement = null))).isEqualTo(MmsPlace.Messages(null))
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aHistoryCheckThatFailed_failsOpenToMessagesWithoutJudging_forAnUnknownSender() {
        val check = Check(answer = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), history = false)

        assertThat(place(check)).isEqualTo(MmsPlace.Messages(null))
        assertThat(check.judged).isEqualTo(0)
        assertThat(store.calls).isEmpty()
        assertThat(check.logged.single().first).isEqualTo("ERROR")
    }

    @Test
    fun e11_aHistoryCheckThatFailed_stillKeepsABlockRuleSenderInSpam() {
        val check = Check(answer = FilterDecision.Block(DecisionReason.BLOCK_RULE, 4L), history = false)

        assertThat(place(check)).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.rowSilent[row]).isTrue()
        assertThat(store.spamRows[THREAD]?.second).isEqualTo(SpamCause.BLOCKED)
        assertThat(store.ruleHits).containsExactly(4L)
        assertThat(check.judged).isEqualTo(0)
        assertThat(check.logged).containsExactly(Triple("BLOCK", "BLOCK_RULE", 4L))
    }

    // Notices

    @Test
    fun aSpamNotice_getsItsSpamChatReadAndSeen_spamRowFirst_andTheListsAreTold() {
        val inSpam = mms.revealNotice(notice, SENDER, SpamCause.BLOCKED)

        assertThat(inSpam).isTrue()
        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[notice]).isEqualTo(thread)
        assertThat(store.rowSilent[notice]).isTrue()
        assertThat(store.spamRows[thread]?.second).isEqualTo(SpamCause.BLOCKED)
        assertThat(store.calls)
            .containsExactly("threadOf", "noteSpamArrival", "isSpamThreadOf", "setThread(silent=true)").inOrder()
        assertThat(store.shown).containsExactly(thread)
    }

    @Test
    fun e3_aQuietedNoticeWhoseSpamRowFailed_showsInMessagesUnread() {
        store.rowSilent[notice] = true
        store.failSpamRow = true

        assertThat(mms.revealNotice(notice, SENDER, SpamCause.UNKNOWN)).isFalse()

        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[notice]).isFalse()
    }

    @Test
    fun e3_aQuietedNoticeOfASenderNoLongerInSpam_showsInMessagesUnread() {
        store.rowSilent[notice] = true

        assertThat(mms.revealNotice(notice, SENDER, null)).isFalse()

        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aNoticeWithoutSender_isLeftAlone() {
        assertThat(mms.revealNotice(notice, " ", SpamCause.UNKNOWN)).isFalse()
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aNoticeRevealedInSpam_countsOnce_withItsRuleAndAnExplicitMark() {
        assertThat(mms.revealNotice(notice, SENDER, SpamCause.BLOCKED, ruleId = 6L)).isTrue()

        val thread = store.threads.getValue(SENDER)
        assertThat(store.ruleHits).containsExactly(6L)
        assertThat(store.spamEvents).isEqualTo(1)
        assertThat(store.marks[notice]).isEqualTo(NoticeMark.Counted(thread))
    }

    @Test
    fun e26_aNoticeGoneBeforeItsChatIsWritten_isNotCountedMarkedOrRecorded_andItsEmptyChatGoes() {
        // A late result placed (and counted) its MMS and the library deleted the notice while the sweep placed it.
        store.goneRows += notice

        val result = mms.revealNotice(notice, SENDER, SpamCause.BLOCKED, ruleId = 6L)

        // J3-2: "gone", so the caller writes no decision-log row; the Spam row this call made goes with the empty chat.
        assertThat(result).isNull()
        val thread = store.threads.getValue(SENDER)
        assertThat(store.forgotten).containsExactly(thread)
        assertThat(store.spamRows).isEmpty()
        assertThat(store.ruleHits).isEmpty()
        assertThat(store.spamEvents).isEqualTo(0)
        assertThat(store.marks).isEmpty()
        assertThat(store.shown).isEmpty()
    }

    @Test
    fun j3_2_aGoneNoticeInAChatWithMessages_keepsTheChat_andTakesItsRaiseBack() {
        store.threads[SENDER] = THREAD
        store.threadsWithMessages += THREAD
        store.spamRows[THREAD] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        store.goneRows += notice

        assertThat(mms.revealNotice(notice, SENDER, SpamCause.UNKNOWN)).isNull()

        assertThat(store.forgotten).isEmpty()
        assertThat(store.spamRows[THREAD]?.third).isEqualTo(1)
    }

    @Test
    fun j3_aWriteThatThrows_takesTheRaiseBack_andTheCallerFailsOpen() {
        store.threads[SENDER] = THREAD
        store.spamRows[THREAD] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        store.failSetThread = true

        val thrown = runCatching { mms.revealNotice(notice, SENDER, SpamCause.UNKNOWN) }

        assertThat(thrown.isFailure).isTrue()
        assertThat(store.spamRows[THREAD]?.third).isEqualTo(1)
        assertThat(store.marks).isEmpty()
    }

    @Test
    fun j3_4_theNoticeVerdict_saysWhyANoticeIsDelivered() {
        val allowed = FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION)
        val blocked = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)

        assertThat(IncomingMms.noticeVerdict(SpamCause.UNKNOWN, { true }) { allowed })
            .isEqualTo(NoticeVerdict(null, "KNOWN_CONVERSATION"))
        assertThat(IncomingMms.noticeVerdict(SpamCause.UNKNOWN, { false }) { blocked })
            .isEqualTo(NoticeVerdict(null, "HISTORY_CHECK_FAILED"))
        assertThat(IncomingMms.noticeVerdict(SpamCause.UNKNOWN, { true }) { null })
            .isEqualTo(NoticeVerdict(null, "FILTER_FAILED"))
        assertThat(IncomingMms.noticeVerdict(SpamCause.UNKNOWN, { true }) { blocked }.cause)
            .isEqualTo(SpamCause.UNKNOWN)
    }

    @Test
    fun aNoticeRevealedInMessages_isNotCountedOrMarked() {
        store.failSpamRow = true

        mms.revealNotice(notice, SENDER, SpamCause.UNKNOWN, ruleId = 6L)

        assertThat(store.ruleHits).isEmpty()
        assertThat(store.spamEvents).isEqualTo(0)
        assertThat(store.marks).isEmpty()
    }

    // E-8, E-22: one message, one count

    @Test
    fun aDownloadingNoticeThenItsDownload_raisesTheSpamCountOnceAndCountsOneHit() {
        store.threads[SENDER] = THREAD
        store.spamRows[THREAD] = Triple(SENDER, SpamCause.BLOCKED, 0)

        val place = place(Check(judgement = spam(SpamCause.BLOCKED, ruleId = 7L)))

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.spamRows[THREAD]?.third).isEqualTo(1)
        assertThat(store.ruleHits).containsExactly(7L)
        assertThat(store.spamEvents).isEqualTo(1)
    }

    @Test
    fun aRevealedNoticeDownloadedLater_isNotCountedAgain() {
        mms.revealNotice(notice, SENDER, SpamCause.BLOCKED, ruleId = 7L)
        val thread = store.threads.getValue(SENDER)

        val place = mms.placeDownloaded(
            row = row, threadId = thread, sender = SENDER, recipients = 1,
            check = Check(judgement = spam(SpamCause.BLOCKED, ruleId = 7L)), noticeCounted = true,
        )

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.BLOCKED))
        assertThat(store.spamRows[thread]?.third).isEqualTo(1)
        assertThat(store.ruleHits).containsExactly(7L)
        assertThat(store.spamEvents).isEqualTo(1)
    }

    @Test
    fun aCountedNoticeWhoseMmsGoesToMessages_stillCountsItsAllowedWord() {
        val judgement = Judgement(
            SenderVerdict.Messages(DecisionReason.ALLOW_RULE, 3L), SenderFact.AutoAllow(3L), factWritten = true,
        )

        val place = place(Check(judgement = judgement), counted = true)

        assertThat(place).isEqualTo(MmsPlace.Messages(DecisionReason.ALLOW_RULE))
        assertThat(store.ruleHits).containsExactly(3L)
    }

    @Test
    fun aCountedNoticeWhoseMmsWentToAGroup_lowersOnlyTheCountItAddedToTheChatItKeeps() {
        store.threadsWithMessages += 55L
        store.spamRows[55L] = Triple(SENDER, SpamCause.UNKNOWN, 2)

        mms.forgetNoticeChat(noticeThread = 55L, landedIn = 66L, countedIn = 55L)
        assertThat(store.spamRows[55L]?.third).isEqualTo(1)

        // A notice the user only read (no mark) added nothing: nothing is lowered.
        mms.forgetNoticeChat(noticeThread = 55L, landedIn = 66L, countedIn = null)
        assertThat(store.spamRows[55L]?.third).isEqualTo(1)
        assertThat(store.forgotten).isEmpty()
    }

    // E-19: the history check before a notice gets a Spam row

    @Test
    fun aNoticeCause_afterAFailedHistoryCheck_keepsOnlyABlockRule() {
        val blocked = FilterDecision.Block(DecisionReason.BLOCK_RULE, 1L)

        assertThat(IncomingMms.noticeCause(SpamCause.UNKNOWN, { false }) { blocked }).isNull()
        assertThat(IncomingMms.noticeCause(SpamCause.BLOCKED, { false }) { null }).isEqualTo(SpamCause.BLOCKED)
        assertThat(IncomingMms.noticeCause(null, { true }) { blocked }).isNull()
    }

    @Test
    fun aNoticeCause_afterThePassedHistoryCheck_isAskedAgain() {
        val allowed = FilterDecision.Allow(DecisionReason.GROUP_MEMBER)
        val unknown = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)

        // The check allowed the sender (a number the user wrote to): no Spam row.
        assertThat(IncomingMms.noticeCause(SpamCause.UNKNOWN, { true }) { allowed }).isNull()
        assertThat(IncomingMms.noticeCause(SpamCause.UNKNOWN, { true }) { unknown }).isEqualTo(SpamCause.UNKNOWN)
        // A block rule is not asked again: history never allows a blocked sender.
        assertThat(IncomingMms.noticeCause(SpamCause.BLOCKED, { true }) { allowed }).isEqualTo(SpamCause.BLOCKED)
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
