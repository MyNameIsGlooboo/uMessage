package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.MmsDownloadFailure
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.data.UncachedChats
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.UnknownNotice
import com.glooboo.umessage.spam.mms.MmsSenderCheck
import com.glooboo.umessage.spam.mms.NoticeRow
import com.glooboo.umessage.testing.FakeMmsProvider
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.MmsPduFixtures
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Provider truth, 6.2 (step E, E-7): what the receiver does with a placed MMS. A one-to-one Spam MMS posts nothing,
 * is not forwarded and does not unarchive its chat; a blocked member's group MMS is silent; an unknown member's group
 * MMS and a Messages MMS are announced and forwarded. The participants are counted without the user's own numbers.
 * The announce, forward and chat steps are recorded instead of run (Robolectric has no MMS provider for the library).
 */
@RunWith(AndroidJUnit4::class)
class MmsArrivalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FakeMmsSpamStore()

    private val announced = mutableListOf<DecisionReason?>()
    private val forwarded = mutableListOf<ForwardCandidate>()
    private val updated = mutableListOf<Boolean>()
    private var judged = 0

    private val realUncachedLane = UncachedChats.lane

    /** R3-1: no background caching thread in these tests; the chat step is recorded ([updated]). */
    @Before
    fun setUp() {
        UncachedChats.lane = { _, _ -> }
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        UncachedChats.lane = realUncachedLane
    }

    private class Check(val answer: FilterDecision?, val judgement: Judgement?, val onJudge: () -> Unit) :
        MmsSenderCheck {
        override fun probe(): FilterDecision? = answer
        override fun judge(): Judgement? = judgement.also { onJudge() }
        override fun historyChecked(): Boolean = true
        override fun log(decision: String, reason: String, ruleId: Long?) = Unit
    }

    /** M-T5: a filter that throws on every call; [blockRule] answers the probe instead when set. */
    private class BrokenFilter(
        val history: Boolean? = true,
        val blockRule: FilterDecision? = null,
    ) : MmsSenderCheck {
        override fun probe(): FilterDecision? = blockRule ?: error("filter broken")
        override fun judge(): Judgement? = error("filter broken")

        /** [history] null: the history check throws too. */
        override fun historyChecked(): Boolean = history ?: error("history check broken")
        override fun log(decision: String, reason: String, ruleId: Long?) = Unit
    }

    /** A sender check that records its rows and whether it was judged with or without a row (J3-1). */
    private class RecordingCheck(val judgement: Judgement?, val answer: FilterDecision? = null) : MmsSenderCheck {
        val rows = mutableListOf<String>()
        var loudJudges = 0
        var quietJudges = 0

        override fun probe(): FilterDecision? = answer
        override fun judge(): Judgement? = judgement.also { loudJudges++ }
        override fun judgeQuietly(): Judgement? = judgement.also { quietJudges++ }
        override fun historyChecked(): Boolean = true
        override fun log(decision: String, reason: String, ruleId: Long?) {
            rows += decision
        }
    }

    private fun handler(check: MmsSenderCheck) = handler().apply {
        senderCheck = { _: Context, _: IncomingMessage, _: Long -> check }
    }

    private fun handler(
        probe: FilterDecision? = FilterDecision.Allow(DecisionReason.CONTACT),
        judgement: Judgement? = Judgement(SenderVerdict.Messages(DecisionReason.CONTACT)),
    ) = MmsDownloadHandler().apply {
        incomingMms = { IncomingMms(store) }
        senderCheck = { _: Context, _: IncomingMessage, _: Long -> Check(probe, judgement) { judged++ } }
        announce = { _, _, _, reason -> announced += reason }
        forwardOf = { _, candidate -> forwarded += candidate }
        updateChatOf = { _, _, unarchive -> updated += unarchive }
    }

    private fun mms(thread: Long, vararg numbers: String) = Message(
        id = 7L, body = "photo", type = 1, status = -1,
        participants = ArrayList(numbers.mapIndexed { i, number ->
            val phone = arrayListOf(PhoneNumber(number, 0, "", number))
            SimpleContact(i + 1, i + 1, number, "", phone, ArrayList(), ArrayList())
        }),
        date = 1_700_000_000, read = false, threadId = thread, isMMS = true, attachment = null,
        senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 1,
    )

    @Test
    fun aOneToOneSpamMms_postsNothingIsNotForwardedAndDoesNotUnarchive() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(updated).containsExactly(false)
        assertThat(store.rowSilent.values).containsExactly(true)
        assertThat(store.spamRows[12L]?.second).isEqualTo(SpamCause.UNKNOWN)
    }

    @Test
    fun aGroupMmsFromABlockedMember_isSilentAndNotForwarded() {
        val handler = handler(probe = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(updated).containsExactly(false)
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aGroupMmsFromAnUnknownMember_isAnnouncedAndForwarded() {
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).containsExactly(DecisionReason.UNKNOWN_SENDER)
        assertThat(forwarded.single().isMms).isTrue()
        assertThat(judged).isEqualTo(0)
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aMessagesMms_isAnnouncedWithItsReasonAndForwarded() {
        handler().placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(DecisionReason.CONTACT)
        assertThat(forwarded).hasSize(1)
        assertThat(updated).isEmpty()
        assertThat(judged).isEqualTo(1)
    }

    @Test
    fun mT5_aFilterErrorOnAOneToOneMms_endsInMessagesUnread_announcedAndForwarded() {
        handler(BrokenFilter()).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).hasSize(1)
        // Unread as the library stored it: a fail-open place writes nothing to the row (J3-7).
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.calls).doesNotContain("setSilent(true)")
        assertThat(store.calls).isEmpty()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun j3_7_aHistoryCheckAndAFilterThatBothThrow_endInMessages_announcedAndForwardedOnce() {
        handler(BrokenFilter(history = null)).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).hasSize(1)
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.spamRows).isEmpty()
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun mT5_aFilterErrorOnAGroupMms_endsInTheGroupInMessages_announcedAndForwarded() {
        handler(BrokenFilter()).placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).hasSize(1)
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.calls).isEmpty()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun mT5_aBlockedSendersMms_isNeverForwarded_alsoWhenTheHistoryCheckFailed() {
        val blocked = BrokenFilter(history = false, blockRule = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L))

        handler(blocked).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).isEmpty()
        assertThat(forwarded).isEmpty()
        assertThat(store.rowSilent.values).containsExactly(true)
    }

    @Test
    fun mT5_aSpamMmsWhoseSpamPlaceFails_isAnnouncedUnreadInMessages_butNeverForwarded() {
        store.failSpamRow = true
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(null)
        assertThat(forwarded).isEmpty()
        assertThat(store.rowSilent.values).containsExactly(false)
        assertThat(store.calls).doesNotContain("setSilent(true)")
    }

    @Test
    fun e15_aChatWithTheSenderAndTheUsersOwnNumber_isAGroupAsTheListsShowIt_soMessagesNeverReadAndSilent() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement = spam)

        handler.placeAndAnnounce(context, mms(12L, SENDER, OWN), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(announced).containsExactly(DecisionReason.UNKNOWN_SENDER)
        assertThat(store.rowSilent).isEmpty()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun e10_oneUnreadableRecipient_makesItAGroup() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).placeAndAnnounce(context, mms(12L, SENDER, ""), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun aDownloadingNoticeOfASenderWithAMessagesChat_andAGroupResult_leavesThatChatInMessagesWithoutASpamRow() {
        store.threads[SENDER] = 20L
        store.threadsWithMessages += 20L
        // A downloading Spam notice keeps the dummy thread (no early Spam row, nothing in the sender's chat).
        val notice = Uri.parse("content://mms/inbox/42")

        handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowThread[notice]).isNull()
        assertThat(store.forgotten).isEmpty()
        assertThat(announced).hasSize(1)
    }

    @Test
    fun e10_aChatWhoseParticipantsCannotBeRead_isTreatedAsAGroupAndAnnounced() {
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement = spam)

        handler.placeAndAnnounce(context, mms(12L), SENDER)

        assertThat(judged).isEqualTo(0)
        assertThat(announced).hasSize(1)
        assertThat(store.rowSilent).isEmpty()
    }

    @Test
    fun theEmptyChatOfTheNotice_isForgottenWhenTheMmsLandsInTheGroup_butNotMmslibsDummyThread() {
        val provider = FakeMmsProvider.install()
        val shown = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .apply { noticeUri = provider.addNotice(SENDER, threadId = 55L) }
        shown.isAddressBlocked(context, SENDER)
        shown.placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)

        val dummy = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .apply { noticeUri = provider.addNotice(SENDER, transactionId = "T-2") }
        dummy.isAddressBlocked(context, SENDER)
        dummy.placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)

        assertThat(store.forgotten).containsExactly(55L)
    }

    @Test
    fun l11_aCountedNoticeWhoseMmsEndsInMessages_getsItsQuietNoticeCancelled_andOneThatStaysInSpamDoesNot() {
        val provider = FakeMmsProvider.install()
        val cancelled = mutableListOf<Long>()
        val notices = object : UnknownNotice {
            override fun post(threadId: Long, sender: String, body: String, at: Long) = Unit
            override fun cancel(threadId: Long) {
                cancelled += threadId
            }
        }
        val counted = provider.addNotice(SENDER, threadId = 55L)
        AndroidMmsSpamStore(context).markNotice(counted, NoticeMark.Counted(55L))
        val rescued = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
            .apply { noticeUri = counted; incomingMms = { IncomingMms(store, notices) } }
        rescued.isAddressBlocked(context, SENDER)

        rescued.placeAndAnnounce(context, mms(55L, SENDER), SENDER)

        assertThat(cancelled).containsExactly(55L)

        cancelled.clear()
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val again = provider.addNotice(SENDER, transactionId = "T-3", threadId = 55L)
        AndroidMmsSpamStore(context).markNotice(again, NoticeMark.Counted(55L))
        val stays = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), judgement = spam)
            .apply { noticeUri = again; incomingMms = { IncomingMms(store, notices) } }
        stays.isAddressBlocked(context, SENDER)

        stays.placeAndAnnounce(context, mms(55L, SENDER), SENDER)

        assertThat(cancelled).isEmpty()
    }

    private fun unreported(receiver: MmsReceiver, file: File, notice: Uri) {
        val broadcast = WatchedBroadcast.attachTo(receiver)
        receiver.onReceive(
            context,
            Intent(context, MmsReceiver::class.java)
                .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file.path)
                .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/unreported")
                .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
                .putExtra(MmsReceivedReceiver.EXTRA_URI, notice)
                .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1),
        )
        assertThat(broadcast.awaitFinished()).isTrue()
    }

    private fun failingReceiver(onProbe: () -> Unit) = MmsReceiver().apply {
        failure = MmsDownloadFailure(
            store = { store },
            probe = { _, _ -> onProbe(); null },
            notify = {},
            toast = {},
            finishLibraryDownload = {},
            log = { _, _, _, _, _ -> },
        )
    }

    @Test
    fun e5_aJunkFile_isAFailedDownload_andTheNoticeStays_withAnMmsProviderPresent() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        store.notices[notice] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)
        var failed = 0
        val receiver = failingReceiver { failed++ }
        val file = File(context.cacheDir, "download.junk.dat").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }

        unreported(receiver, file, notice)

        assertThat(failed).isEqualTo(1)
        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(provider.exists(notice.lastPathSegment!!.toLong())).isTrue()
    }

    @Test
    fun e17_anMmsTheLibraryStoredButDidNotReport_isPlaced_notFailed() {
        var failed = 0
        var asked: Pair<String, String>? = null
        val receiver = failingReceiver { failed++ }.apply {
            storedRow = { _, id, location, _ -> asked = id to location; Uri.parse("content://mms/77") }
        }
        val file = File(context.cacheDir, "download.junk2.dat").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }

        unreported(receiver, file, Uri.parse("content://mms/inbox/42"))

        assertThat(failed).isEqualTo(0)
        assertThat(asked).isEqualTo("T-1" to "http://mmsc.example.com/msg/unreported")
    }

    @Test
    fun e5_aResultTheLibraryCouldNotStore_runsTheFailurePath() {
        var failed = 0
        val receiver = MmsReceiver().apply {
            failure = MmsDownloadFailure(
                store = { store },
                probe = { _, _ -> failed++; null },
                notify = {},
                toast = {},
                finishLibraryDownload = {},
                log = { _, _, _, _, _ -> },
            )
            storedRow = { _, _, _, _ -> null }
        }
        // A good PDU the library cannot store (Robolectric has no MMS provider): neither stored nor an error.
        val pdu = MmsPduFixtures.retrieveConf(context, "hello")
        val file = File(context.cacheDir, "download.unstorable.dat").apply { writeBytes(pdu) }
        val notice = Uri.parse("content://mms/inbox/42")
        store.notices[notice] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)
        val broadcast = WatchedBroadcast.attachTo(receiver)

        receiver.onReceive(
            context,
            Intent(context, MmsReceiver::class.java)
                .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file.path)
                .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/unstorable")
                .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
                .putExtra(MmsReceivedReceiver.EXTRA_URI, notice)
                .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1),
        )

        assertThat(broadcast.awaitFinished()).isTrue()
        assertThat(failed).isEqualTo(1)
        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
    }

    @Test
    fun j28_2_aCountedNoticeWhoseSentHistoryCannotBeRead_failsOpenToMessagesWithAnErrorRow() {
        val notice = Uri.parse("content://mms/inbox/42")
        store.notices[notice] = NoticeRow(threadId = 12L, sender = SENDER)
        store.threads[SENDER] = 12L
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        store.marks[notice] = NoticeMark.Counted(12L)
        val logged = mutableListOf<String>()
        val failure = MmsDownloadFailure(
            store = { store },
            probe = { _, _ -> FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) },
            notify = {}, toast = {}, finishLibraryDownload = {}, tracker = MmsDownloadTracker(),
            history = { _, _ -> true },
            log = { _, _, decision, reason, _ -> logged += "$decision/$reason" },
            wroteTo = { _, _ -> null },
        )

        failure.handle(
            context,
            Intent(context, MmsReceiver::class.java)
                .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, File(context.cacheDir, "download.j28.dat").path)
                .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/j28")
                .putExtra(MmsReceivedReceiver.EXTRA_URI, notice)
                .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 1),
        )

        // J28-2: counted in its Spam chat, but the sent history is unreadable: Messages, unread, one ERROR row, and
        // the count and mark the notice raised are released with its quiet notice.
        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(store.rowThread[notice]).isEqualTo(12L)
        assertThat(store.spamRows[12L]?.third).isEqualTo(0)
        assertThat(store.marks[notice]).isNull()
        assertThat(logged).containsExactly("ERROR/HISTORY_CHECK_FAILED")
    }

    @Test
    fun e22_onlyAnExplicitlyCountedNotice_lowersTheCountOfTheChatItLeaves_andItsMarkIsThenForgotten() {
        val provider = FakeMmsProvider.install()
        val counted = provider.addNotice(SENDER, threadId = 55L, read = true)
        val onlyRead = provider.addNotice(SENDER, threadId = 56L, read = true, transactionId = "T-2")
        store.threadsWithMessages += setOf(55L, 56L)
        store.spamRows[55L] = Triple(SENDER, SpamCause.UNKNOWN, 2)
        store.spamRows[56L] = Triple(SENDER, SpamCause.UNKNOWN, 2)
        val marks = AndroidMmsSpamStore(context)
        marks.markNotice(counted, NoticeMark.Counted(55L))

        listOf(counted, onlyRead).forEach { notice ->
            handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
                .apply { noticeUri = notice }
                .also { it.isAddressBlocked(context, SENDER) }
                .placeAndAnnounce(context, mms(66L, SENDER, OTHER), SENDER)
        }

        assertThat(store.spamRows[55L]?.third).isEqualTo(1)
        // Read by the user, never counted: nothing is lowered.
        assertThat(store.spamRows[56L]?.third).isEqualTo(2)
        assertThat(marks.markOf(counted)).isNull()
    }

    @Test
    fun e22_aCountedNoticeInTheSameChat_isNotCountedAgainWhenItsMmsIsPlaced() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER, threadId = 12L, read = true)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))

        handler(judgement = spam).apply { noticeUri = notice }
            .also { it.isAddressBlocked(context, SENDER) }
            .placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(store.spamRows[12L]?.third).isEqualTo(1)
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun j3_1_aNoticeTheSweepCountedWhileTheLibraryStoredItsMms_isNotCountedAgain() {
        val provider = FakeMmsProvider.install()
        // A downloading Spam notice: mmslib's dummy thread, no mark when the result arrives.
        val notice = provider.addNotice(SENDER)
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(judgement = spam).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        // Meanwhile the sweep places the notice in the sender's Spam chat and counts it there.
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(store.spamRows[12L]?.third).isEqualTo(1)
        assertThat(store.spamEvents).isEqualTo(0)
        assertThat(announced).isEmpty()
    }

    @Test
    fun j3_1_whenTheSweepLoggedTheNotice_thePlacementWritesNoSecondRow() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        val check = RecordingCheck(Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)))
        val handler = handler(check).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        // Judged with its facts as always, but without a decision-log row: the sweep's row is this message's one.
        assertThat(check.quietJudges).isEqualTo(1)
        assertThat(check.loudJudges).isEqualTo(0)
        assertThat(check.rows).isEmpty()
    }

    @Test
    fun j3_1_withoutASweepInBetween_thePlacementWritesItsOwnRow() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        val check = RecordingCheck(Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)))
        val handler = handler(check).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        // E-13: the placement writes the one row itself, after the rescue, so the judgement is always quiet.
        assertThat(check.loudJudges).isEqualTo(0)
        assertThat(check.quietJudges).isEqualTo(1)
        assertThat(check.rows).containsExactly("BLOCK")
    }

    @Test
    fun r3_2_whenTheSweepLoggedTheNotice_anMmsThatEndsInMessages_writesItsOwnRow() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        // The sender is let in now (a contact): the sweep's BLOCK row does not say where this MMS went.
        val check = RecordingCheck(Judgement(SenderVerdict.Messages(DecisionReason.CONTACT)))
        val handler = handler(check).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(announced).containsExactly(DecisionReason.CONTACT)
        // Judged quietly (facts once), then the row the judge would have written.
        assertThat(check.quietJudges).isEqualTo(1)
        assertThat(check.loudJudges).isEqualTo(0)
        assertThat(check.rows).containsExactly("ALLOW")
    }

    @Test
    fun r3_2_whenTheSweepLoggedTheNotice_anMmsThatLandsInAGroup_writesThePlacementsRow() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        val check = RecordingCheck(null, answer = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER))
        val handler = handler(check).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        assertThat(check.rows).containsExactly("DELIVER")
    }

    @Test
    fun r2_4_aNoticeWithAFailedTryAtTheFirstRead_thatTheSweepCountsMeanwhile_isNotCountedAgain() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        // An earlier sweep failed on it once.
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Failed(1))
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(judgement = spam).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        assertThat(store.spamRows[12L]?.third).isEqualTo(1)
        assertThat(store.spamEvents).isEqualTo(0)
    }

    @Test
    fun r2_4_aCountedMarkOfAnotherChatAtTheFirstRead_isNotReadAgain() {
        val provider = FakeMmsProvider.install()
        // Counted in chat 77, but the notice has no chat now: that mark is stale, and no second read takes it.
        val notice = provider.addNotice(SENDER)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(77L))
        val spam = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
        val handler = handler(judgement = spam).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)

        handler.placeAndAnnounce(context, mms(12L, SENDER), SENDER)

        // Counted here as a new message.
        assertThat(store.spamRows[12L]?.third).isEqualTo(2)
        assertThat(store.spamEvents).isEqualTo(1)
    }

    @Test
    fun j3_1_aNoticeTheSweepCountedWhoseMmsLandsInAGroup_leavesNoCountInTheSendersChat() {
        val provider = FakeMmsProvider.install()
        val notice = provider.addNotice(SENDER)
        val handler = handler(probe = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)).apply { noticeUri = notice }
        handler.isAddressBlocked(context, SENDER)
        store.spamRows[12L] = Triple(SENDER, SpamCause.UNKNOWN, 1)
        AndroidMmsSpamStore(context).markNotice(notice, NoticeMark.Counted(12L))

        handler.placeAndAnnounce(context, mms(40L, SENDER, OTHER), SENDER)

        // The sender's chat held only the notice: it is empty now, so the app's rows of it go (R4).
        assertThat(store.forgotten).containsExactly(12L)
        assertThat(store.spamRows[12L]).isNull()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550004321"
        const val OWN = "+15550009999"
    }
}
