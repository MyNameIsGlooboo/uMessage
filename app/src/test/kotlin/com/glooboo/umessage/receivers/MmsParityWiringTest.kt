package com.glooboo.umessage.receivers

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.MmsDownloadFailure
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.AndroidSpamPlatform
import com.glooboo.umessage.spam.data.ArrivalFacts
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.AppUnknownNotice
import com.glooboo.umessage.spam.mms.NoticeRow
import com.glooboo.umessage.spam.mms.SpamNotice
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * J28 wiring: the MMS paths reach the real "wrote to" rescue ([ArrivalFacts], stubbed only at its store read) and the
 * real opt-in notice ([AppUnknownNotice]) through their own defaults, and the notice shows what the SMS one shows.
 * The number is made up.
 */
@RunWith(AndroidJUnit4::class)
class MmsParityWiringTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val realWroteTo = ArrivalFacts.wroteTo
    private val store = FakeMmsSpamStore()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private var wrote = ContactLookup.NOT_FOUND
    private val saved = Uri.parse("content://mms/inbox/42")

    @Before
    fun setUp() {
        db = SpamTestWiring.freshSpamDatabase(app)
        repo = DefaultSpamRepository(AndroidSpamPlatform(app), db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE))
            .write(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        FirstRun.reload(app)
        ArrivalFacts.wroteTo = { _, _ -> wrote }
    }

    @After
    fun tearDown() {
        ArrivalFacts.wroteTo = realWroteTo
        FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(FirstRunState())
        FirstRun.reload(app)
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun noticeOption(on: Boolean) = runBlocking { repo.updateSettings { it.copy(notifyOnBlock = on) } }

    private fun noticeText(): String? = app.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.tag == "spamless_blocked_chat" }?.notification?.extras
        ?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun failure(log: (String) -> Unit = {}, wroteTo: ((Context, String) -> Boolean?)? = null) =
        if (wroteTo != null) {
            MmsDownloadFailure(
                store = { store },
                probe = { _, _ -> FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) },
                notify = {}, toast = {}, finishLibraryDownload = {}, tracker = MmsDownloadTracker(),
                history = { _, _ -> true }, log = { _, _, d, r, _ -> log("$d/$r") },
                wroteTo = wroteTo,
            )
        } else {
            MmsDownloadFailure(
                store = { store },
                probe = { _, _ -> FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) },
                notify = {}, toast = {}, finishLibraryDownload = {}, tracker = MmsDownloadTracker(),
                history = { _, _ -> true }, log = { _, _, d, r, _ -> log("$d/$r") },
            )
        }

    // The shared option read and the notice itself (the same one the SMS path posts)

    @Test
    fun theOptionOff_postsNothing() {
        noticeOption(false)

        AppUnknownNotice(app).post(THREAD, SENDER, "hello", 1L)

        assertThat(ChatNotices.isShown(app, THREAD)).isFalse()
        assertThat(AppUnknownNotice.isOn(app)).isFalse()
    }

    @Test
    fun theOptionOn_postsTheQuietNoticeWithTheMmsText_orTheMmsLabelWithoutOne() {
        noticeOption(true)
        assertThat(AppUnknownNotice.isOn(app)).isTrue()

        AppUnknownNotice(app).post(THREAD, SENDER, "hello", 1L)
        assertThat(ChatNotices.isShown(app, THREAD)).isTrue()
        assertThat(noticeText()).isEqualTo("hello")

        AppUnknownNotice(app).post(THREAD, SENDER, "", 2L)
        assertThat(noticeText()).isEqualTo(app.getString(R.string.spam_engine_mms_blocked))
    }

    @Test
    fun cancel_removesTheChatNotice() {
        noticeOption(true)
        AppUnknownNotice(app).post(THREAD, SENDER, "hello", 1L)

        AppUnknownNotice(app).cancel(THREAD)

        assertThat(ChatNotices.isShown(app, THREAD)).isFalse()
    }

    // The defaults reach the real implementations

    @Test
    fun theHandlerAndThePushDownloadDefaults_makeAnIncomingMmsWithTheRealNotice() {
        assertThat(MmsDownloadHandler().incomingMms(app).unknownNotice).isInstanceOf(AppUnknownNotice::class.java)
        assertThat(MmsPushDownload().incoming(app).unknownNotice).isInstanceOf(AppUnknownNotice::class.java)
    }

    @Test
    fun appSenderCheck_asksTheRealSentHistory_unreadableFailsOpen_notFoundKeepsTheVerdict() {
        val check = AppSenderCheck(app, IncomingMessage(SENDER, "", 1, isMms = true), THREAD)
        val judged = Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER, null))

        wrote = ContactLookup.UNKNOWN
        assertThat(check.wroteToOnArrival(judged)).isNull()

        wrote = ContactLookup.NOT_FOUND
        assertThat(check.wroteToOnArrival(judged)).isEqualTo(judged)

        // A blocked sender is never asked.
        wrote = ContactLookup.UNKNOWN
        val blocked = Judgement(SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, 3L))
        assertThat(check.wroteToOnArrival(blocked)).isEqualTo(blocked)
    }

    @Test
    fun theDefaultRecheck_asksTheRealSentHistory_soAnUnreadableOneFailsOpenToMessages() {
        noticeOption(true)
        wrote = ContactLookup.UNKNOWN

        var told = 0
        MmsPushDownload.reportStartFailed(
            app, saved, SENDER, SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = false),
            store = { store }, notify = { told++ },
        )

        assertThat(store.rowSilent[saved]).isFalse()
        assertThat(told).isEqualTo(1)
        assertThat(ChatNotices.isShown(app, store.threads.getValue(SENDER))).isFalse()
    }

    @Test
    fun theDefaultRecheckAndNotice_keepAnUnknownSenderInSpamAndPostTheRealNotice() {
        noticeOption(true)
        wrote = ContactLookup.NOT_FOUND

        var told = 0
        MmsPushDownload.reportStartFailed(
            app, saved, SENDER, SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = false),
            store = { store }, notify = { told++ },
        )

        assertThat(store.rowSilent[saved]).isTrue()
        assertThat(told).isEqualTo(0)
        assertThat(ChatNotices.isShown(app, store.threads.getValue(SENDER))).isTrue()
    }

    @Test
    fun theFailurePathsDefaultNotice_isTheRealOne() {
        noticeOption(true)
        store.notices[saved] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)

        failure(wroteTo = { _, _ -> false }).handle(app, failedResult())

        assertThat(ChatNotices.isShown(app, store.threads.getValue(SENDER))).isTrue()
    }

    @Test
    fun theFailurePathsDefaultRescue_isTheRealSentHistory_andAFailOpenLogsOneErrorRow() {
        noticeOption(true)
        wrote = ContactLookup.UNKNOWN
        store.notices[saved] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)
        val logged = mutableListOf<String>()

        failure(log = { logged += it }).handle(app, failedResult())

        // Unreadable sent history: Messages, unread, one ERROR row (J28-2), no quiet notice.
        assertThat(store.rowSilent[saved]).isFalse()
        assertThat(logged).containsExactly("ERROR/HISTORY_CHECK_FAILED")
        assertThat(ChatNotices.isShown(app, store.threads.getValue(SENDER))).isFalse()
    }

    private fun failedResult() = Intent(app, MmsReceiver::class.java)
        .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, File(app.cacheDir, "wiring.missing.dat").path)
        .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, "http://mmsc.example.com/msg/wiring")
        .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, "T-9")
        .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, 2)
        .putExtra(MmsReceivedReceiver.EXTRA_URI, saved)

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 12L
    }
}
