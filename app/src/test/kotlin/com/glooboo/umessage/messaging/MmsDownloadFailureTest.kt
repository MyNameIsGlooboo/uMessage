package com.glooboo.umessage.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.receivers.MmsPushDownload
import com.glooboo.umessage.receivers.MmsReceiver
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.NoticeRow
import com.glooboo.umessage.testing.FakeMmsProvider
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Provider truth, answer 8 (step E): a failed MMS download keeps its notice, which gets its chat (in Spam when the
 * sender's chat is there) and shows "Tap to try again". A failure in a Spam chat posts nothing (E-2, lead decision);
 * in Messages a toast and the notification tell the user.
 */
@RunWith(AndroidJUnit4::class)
class MmsDownloadFailureTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = FakeMmsSpamStore()
    private val tracker = MmsDownloadTracker()
    private val notice = Uri.parse("content://mms/inbox/42")

    private var decision: FilterDecision? = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)
    private val probed = mutableListOf<IncomingMessage>()
    private var toasts = 0
    private var notifications = 0
    private val finished = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private var historyChecked = true

    private val failure = MmsDownloadFailure(
        store = { store },
        probe = { _, message -> probed += message; decision },
        notify = { notifications++ },
        toast = { toasts++ },
        finishLibraryDownload = { finished += it },
        tracker = tracker,
        log = { _, _, decision, _, _ -> logged += decision },
        history = { _, _ -> historyChecked },
    )

    @Before
    fun setUp() {
        MmsPushDownload.clearStarted()
        // E-1: a notice saved for an automatic download has mmslib's dummy thread, which is no chat.
        store.notices[notice] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        resetSharedMessagesDatabase()
        MmsPushDownload.clearStarted()
    }

    private fun result(file: File? = missingFile(), uri: Uri? = notice) = Intent(context, MmsReceiver::class.java)
        .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file?.path)
        .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, LOCATION)
        .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-1")
        .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 2)
        .apply { uri?.let { putExtra(MmsReceivedReceiver.EXTRA_URI, it) } }

    private fun missingFile() = File(context.cacheDir, "download.missing.dat").apply { delete() }

    @Test
    fun aResultWithoutADownloadedFile_isAFailure_andOneWithAFileIsNot() {
        val empty = File(context.cacheDir, "download.empty.dat").apply { writeBytes(ByteArray(0)) }
        val full = File(context.cacheDir, "download.full.dat").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertThat(MmsDownloadFailure.isFailed(result(missingFile()))).isTrue()
        assertThat(MmsDownloadFailure.isFailed(result(empty))).isTrue()
        assertThat(MmsDownloadFailure.isFailed(result(full))).isFalse()
        // No path at all: left to the library, as before.
        assertThat(MmsDownloadFailure.isFailed(result(file = null))).isFalse()
    }

    @Test
    fun aFailedSpamDownload_keepsTheNoticeInItsSpamChat_readAndPostingNothing() {
        failure.handle(context, result())

        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[notice]).isEqualTo(thread)
        assertThat(store.rowSilent[notice]).isTrue()
        assertThat(store.spamRows[thread]?.second).isEqualTo(SpamCause.UNKNOWN)
        assertThat(store.shown).containsExactly(thread)
        assertThat(toasts).isEqualTo(0)
        assertThat(notifications).isEqualTo(0)
        assertThat(logged).containsExactly("BLOCK")
        // The sender was probed on the SIM the MMS came in on, without text.
        val probe = probed.single()
        assertThat(probe.address).isEqualTo(SENDER)
        assertThat(probe.body).isEmpty()
        assertThat(probe.subscriptionId).isEqualTo(2)
        assertThat(probe.isMms).isTrue()
    }

    @Test
    fun aFailedDownloadInMessages_keepsTheNoticeInTheSendersChatUnread_withAToast() {
        decision = FilterDecision.Allow(DecisionReason.CONTACT)

        failure.handle(context, result())

        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(store.spamRows).isEmpty()
        assertThat(toasts).isEqualTo(1)
        assertThat(notifications).isEqualTo(1)
        assertThat(logged).containsExactly("DELIVER")
    }

    @Test
    fun theRowTurnsTapToTryAgain_andARetryIsNotRefused() {
        tracker.begin(42L, System.currentTimeMillis())

        failure.handle(context, result())

        assertThat(tracker.state.value[42L]).isEqualTo(MmsDownloadTracker.Entry.Failed)
        assertThat(MmsPushDownload.isStarted(LOCATION)).isFalse()
        // mmslib's own record of the download is closed too, or its next download of it would be ignored.
        assertThat(finished).containsExactly(LOCATION)
    }

    @Test
    fun aNoticeAlreadyInItsSpamChat_staysThere_isNotCountedAgain_andPostsNothing() {
        decision = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L)
        store.notices[notice] = NoticeRow(threadId = 77L, sender = SENDER, read = true)
        store.marks[notice] = NoticeMark.Counted(77L)
        store.spamRows[77L] = Triple(SENDER, SpamCause.BLOCKED, 1)

        failure.handle(context, result())

        assertThat(store.rowThread).isEmpty()
        assertThat(store.spamRows[77L]?.third).isEqualTo(1)
        assertThat(logged).containsExactly("BLOCK")
        assertThat(toasts).isEqualTo(0)
        assertThat(notifications).isEqualTo(0)
    }

    @Test
    fun aParkedNoticeInItsSpamChat_isCountedOnceNow_readAndPostingNothing() {
        decision = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L)
        store.notices[notice] = NoticeRow(threadId = 77L, sender = SENDER, read = false)
        store.threads[SENDER] = 77L
        store.spamRows[77L] = Triple(SENDER, SpamCause.BLOCKED, 1)

        failure.handle(context, result())

        assertThat(store.spamRows[77L]?.third).isEqualTo(2)
        assertThat(store.rowSilent[notice]).isTrue()
        assertThat(notifications).isEqualTo(0)
        // Counted now, once (E-22): its rule, the Spam event and the mark.
        assertThat(store.ruleHits).containsExactly(3L)
        assertThat(store.spamEvents).isEqualTo(1)
        assertThat(store.marks[notice]).isEqualTo(NoticeMark.Counted(77L))
    }

    @Test
    fun e19_aFailedHistoryCheck_keepsAnUnknownSenderOutOfSpam_unreadAndTold() {
        historyChecked = false

        failure.handle(context, result())

        assertThat(store.spamRows).isEmpty()
        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(notifications).isEqualTo(1)
    }

    @Test
    fun e19_aFailedHistoryCheck_stillKeepsABlockRuleSenderInSpam() {
        historyChecked = false
        decision = FilterDecision.Block(DecisionReason.BLOCK_RULE, 3L)

        failure.handle(context, result())

        assertThat(store.spamRows.values.single().second).isEqualTo(SpamCause.BLOCKED)
        assertThat(notifications).isEqualTo(0)
    }

    @Test
    fun aNoticeWithoutSender_isMarkedSoTheSweepLeavesItAlone() {
        store.notices[notice] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = "")

        failure.handle(context, result())

        assertThat(store.marks[notice]).isEqualTo(NoticeMark.Skipped)
    }

    @Test
    fun theSweep_placesOldNoticesWithoutAChat_quietly_andLeavesTheRest() {
        val provider = FakeMmsProvider.install()
        val now = 1_700_000_000_000L
        val tenMinutesAgo = (now - 10 * 60 * 1000L) / 1000
        val lost = provider.addNotice(
            SENDER, transactionId = "T-old", location = "http://mmsc.example.com/msg/old", dateSeconds = tenMinutesAgo,
        )
        val young = provider.addNotice(
            SENDER, transactionId = "T-new", location = "http://mmsc.example.com/msg/new", dateSeconds = now / 1000,
        )
        val placed = provider.addNotice(SENDER, threadId = 5L, transactionId = "T-chat", dateSeconds = tenMinutesAgo)
        // E-20: left from long before (older than the age bound), and one gone at the carrier.
        val ancient = provider.addNotice(SENDER, transactionId = "T-ancient", dateSeconds = tenMinutesAgo - 8 * 86_400)
        val expired = provider.addNotice(
            SENDER, transactionId = "T-expired", location = "http://mmsc.example.com/msg/expired",
            dateSeconds = tenMinutesAgo, expirySeconds = tenMinutesAgo + 60,
        )
        listOf(lost, young, placed, ancient, expired).forEach {
            store.notices[it] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)
        }

        failure.sweep(context, now)

        assertThat(store.rowThread.keys).containsExactly(lost)
        assertThat(store.marks[expired]).isEqualTo(NoticeMark.Skipped)
        assertThat(store.rowSilent[lost]).isTrue()
        assertThat(toasts).isEqualTo(0)
        assertThat(notifications).isEqualTo(0)
        assertThat(logged).containsExactly("BLOCK")
    }

    @Test
    fun e9_aNoticeInARealChatWithoutASpamRow_isJudgedAndPutInSpam() {
        store.notices[notice] = NoticeRow(threadId = 101L, sender = SENDER)
        store.threads[SENDER] = 101L

        failure.handle(context, result())

        assertThat(store.spamRows[101L]?.second).isEqualTo(SpamCause.UNKNOWN)
        assertThat(store.rowSilent[notice]).isTrue()
        assertThat(notifications).isEqualTo(0)
    }

    @Test
    fun e3_aQuietedNoticeOfASenderNowAllowed_isUnreadInMessages_andTheUserIsTold() {
        decision = FilterDecision.Allow(DecisionReason.ALLOW_RULE, 2L)
        store.notices[notice] = NoticeRow(threadId = 101L, sender = SENDER)
        store.threads[SENDER] = 101L
        store.rowSilent[notice] = true

        failure.handle(context, result())

        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(toasts).isEqualTo(1)
        assertThat(notifications).isEqualTo(1)
    }

    @Test
    fun aNoticeThatIsGone_stillTellsTheUser() {
        store.notices.clear()

        failure.handle(context, result())

        assertThat(toasts).isEqualTo(1)
        assertThat(notifications).isEqualTo(1)
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aSpamRowThatCannotBeWritten_showsTheQuietedNoticeInMessagesUnread_andTellsTheUser() {
        store.rowSilent[notice] = true
        store.failSpamRow = true

        failure.handle(context, result())

        assertThat(store.rowThread[notice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[notice]).isFalse()
        assertThat(toasts).isEqualTo(1)
        assertThat(notifications).isEqualTo(1)
    }

    @Test
    fun theReceiver_handsAFailedResultToTheFailurePath_notToTheLibrary_andFinishes() {
        var handled = 0
        val receiver = MmsReceiver().apply {
            failure = MmsDownloadFailure(
                store = { store },
                probe = { _, _ -> handled++; decision },
                notify = {},
                toast = {},
                finishLibraryDownload = {},
                tracker = tracker,
            )
        }
        val broadcast = WatchedBroadcast.attachTo(receiver)

        receiver.onReceive(context, result())

        assertThat(broadcast.awaitFinished()).isTrue()
        assertThat(handled).isEqualTo(1)
        assertThat(store.rowThread[notice]).isNotNull()
    }

    @Test
    fun e1_aNoticeSavedForAnAutomaticDownload_endsInTheSendersChatAfterAFailure_onTheRealStore() {
        val provider = FakeMmsProvider.install()
        FakeTelephonyProvider.install()
        decision = FilterDecision.Allow(DecisionReason.CONTACT)
        val saved = provider.addNotice(SENDER, read = true)
        val real = MmsDownloadFailure(
            probe = { _, _ -> decision }, notify = {}, toast = {}, tracker = tracker, history = { _, _ -> true },
        )

        real.handle(context, result(uri = saved))

        val id = saved.lastPathSegment!!.toLong()
        val thread = provider.value(id, "thread_id")
        assertThat(thread).isNotEqualTo(Long.MAX_VALUE)
        assertThat(thread).isGreaterThan(0L)
        // The notice is still there (the failure path never deletes it), in Messages, unread again (E-3).
        assertThat(provider.exists(id)).isTrue()
        assertThat(provider.value(id, "read")).isEqualTo(0L)
        assertThat(provider.value(id, "seen")).isEqualTo(0L)
    }

    @Test
    fun withTheFlagOff_aFailedResultIsLeftToTheLibrary() {
        flagOff {
            var handled = 0
            val receiver = MmsReceiver().apply {
                failure = MmsDownloadFailure(store = { handled++; store }, notify = {}, toast = {})
            }
            val broadcast = WatchedBroadcast.attachTo(receiver)

            receiver.onReceive(context, result())

            assertThat(broadcast.awaitFinished()).isTrue()
            assertThat(handled).isEqualTo(0)
        }
    }

    @Test
    fun e20_theAppStartSweep_runsWithTheFlagOnOnly() {
        var swept = 0
        val before = MmsDownloadFailure.startSweep
        MmsDownloadFailure.startSweep = { swept++ }
        try {
            MmsDownloadFailure.atAppStart(context)
            flagOff { MmsDownloadFailure.atAppStart(context) }
        } finally {
            MmsDownloadFailure.startSweep = before
        }

        assertThat(swept).isEqualTo(1)
    }

    @Test
    fun e20_aSweepThatIsRunning_isNotStartedASecondTime() {
        val provider = FakeMmsProvider.install()
        val now = 1_700_000_000_000L
        val lost = provider.addNotice(SENDER, transactionId = "T-old", dateSeconds = (now - 10 * 60 * 1000L) / 1000)
        var inner = 0
        // The store's first read starts a second sweep, as a second push would: it must return at once.
        val reentrant = MmsDownloadFailure(
            store = { store.also { if (inner++ == 0) failure.sweep(context, now) } },
            probe = { _, _ -> decision }, notify = {}, toast = {}, tracker = tracker, history = { _, _ -> true },
            log = { _, _, d, _, _ -> logged += d },
        )
        store.notices[lost] = NoticeRow(FakeMmsSpamStore.NO_CHAT, SENDER)

        reentrant.sweep(context, now)

        assertThat(logged).containsExactly("BLOCK")
    }

    /** The rule puts the flag back afterwards. */
    private fun flagOff(block: () -> Unit) {
        ProviderTruthFlag.enabled = false
        block()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val LOCATION = "http://mmsc.example.com/msg/failed"
    }
}
