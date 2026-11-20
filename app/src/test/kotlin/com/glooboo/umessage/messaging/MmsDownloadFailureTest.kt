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

    private val failure = MmsDownloadFailure(
        store = { store },
        probe = { _, message -> probed += message; decision },
        notify = { notifications++ },
        toast = { toasts++ },
        finishLibraryDownload = { finished += it },
        tracker = tracker,
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
        store.notices[notice] = NoticeRow(threadId = 77L, sender = SENDER)
        store.spamRows[77L] = Triple(SENDER, SpamCause.BLOCKED, 1)

        failure.handle(context, result())

        assertThat(store.rowThread).isEmpty()
        assertThat(store.spamRows[77L]?.third).isEqualTo(1)
        assertThat(toasts).isEqualTo(0)
        assertThat(notifications).isEqualTo(0)
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
        val real = MmsDownloadFailure(probe = { _, _ -> decision }, notify = {}, toast = {}, tracker = tracker)

        real.handle(context, result(uri = saved))

        val id = saved.lastPathSegment!!.toLong()
        val thread = provider.value(id, "thread_id")
        assertThat(thread).isNotEqualTo(Long.MAX_VALUE)
        assertThat(thread).isGreaterThan(0L)
        // In Messages, unread again (E-3), although it was quieted before.
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
