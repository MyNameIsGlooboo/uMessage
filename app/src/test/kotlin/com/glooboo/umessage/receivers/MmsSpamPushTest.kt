package com.glooboo.umessage.receivers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.AndroidSpamPlatform
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.SpamNotice
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.NoticeRow
import com.glooboo.umessage.testing.FakeMmsProvider
import com.glooboo.umessage.testing.FakeMmsSpamStore
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Provider truth, 6.2 (step E): the push of an MMS whose sender's chat is in Spam. Nothing goes to the Blocked folder:
 * the notice is saved in the phone's store and downloaded like any MMS within the limits (size known, at most 1 MiB,
 * not expired, not roaming), also from a blocked sender (answer 11), without an early Spam row. Otherwise it is shown
 * in the sender's Spam chat at once, read and seen, and stays a "Tap to download" row there, with one decision-log
 * row. A failed start in Spam posts nothing (E-2).
 */
@RunWith(AndroidJUnit4::class)
class MmsSpamPushTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private val store = FakeMmsSpamStore()

    /** Saved notice rows: uri -> (content location, transaction id). */
    private val saved = linkedMapOf<Uri, Pair<String, String>>()

    /** E-27: whether each saved notice row was asked to keep no chat. */
    private val savedChatless = linkedMapOf<Uri, Boolean>()
    private val startedLocations = mutableListOf<String>()
    private var startFailedWith: SpamNotice? = null
    private var startFailures = 0
    private val logged = mutableListOf<String>()
    private var startFails = false

    @Before
    fun setUp() {
        db = SpamTestWiring.freshSpamDatabase(app)
        val repo =
            DefaultSpamRepository(AndroidSpamPlatform(app), db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        MmsPushDownload.clearStarted()
    }

    @After
    fun tearDown() {
        MmsPushDownload.clearStarted()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    /** The cause after the history check (E-19); the sender's own cause unless a test says otherwise. */
    private var recheck: (SpamNotice) -> SpamCause? = { it.cause }

    /** A push download on stubs that behave like the provider: a repeat is found by transaction id or location. */
    private fun download() = MmsPushDownload(
        isDuplicate = { _, ind -> saved.values.any { it.second == String(ind.transactionId) } },
        persist = { _, ind: NotificationInd, _, chatless ->
            Uri.parse("content://mms/inbox/${saved.size + 40}").also {
                savedChatless[it] = chatless
                saved[it] = String(ind.contentLocation) to String(ind.transactionId)
            }
        },
        lookup = { _, uri -> saved.getValue(uri) },
        start = { _, location, _, _, _ ->
            check(!startFails) { "system refused" }
            startedLocations += location
        },
        onStartFailed = { _, _, _, spam -> startFailures++; startFailedWith = spam },
        tracker = MmsDownloadTracker(),
        incoming = { IncomingMms(store) },
        log = { _, _, decision -> logged += decision },
        recheck = { _, spam -> recheck(spam) },
    )

    private fun receiver(isRoaming: Boolean = false) = MmsPushReceiver().apply {
        pushDownload = download()
        roaming = { _: Context, _: Int -> isRoaming }
    }

    @Test
    fun mP5_aSecondDifferentNoticeFromTheSameSenderWithinSeconds_isKeptAndDownloadedToo() {
        val receiver = receiver()

        receiver.process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/p5-a")))
        receiver.process(app, push(notice(SENDER, "T-2", "http://mmsc.example.com/msg/p5-b")))

        assertThat(saved).hasSize(2)
        assertThat(startedLocations)
            .containsExactly("http://mmsc.example.com/msg/p5-a", "http://mmsc.example.com/msg/p5-b").inOrder()
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun theSameNoticeTwice_isSavedAndDownloadedOnce() {
        val receiver = receiver()
        val data = notice(SENDER, "T-1", "http://mmsc.example.com/msg/again")

        receiver.process(app, push(data))
        receiver.process(app, push(data))

        assertThat(saved).hasSize(1)
        assertThat(startedLocations).hasSize(1)
    }

    @Test
    fun aSpamNoticeWithinTheLimits_isDownloadedIntoTheStoreWithoutAnEarlySpamRow_notIntoBlocked() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/in")))

        val row = saved.keys.single()
        assertThat(startedLocations).containsExactly("http://mmsc.example.com/msg/in")
        // It keeps mmslib's dummy thread: no chat, no Spam row, no count, no row in the log until it is placed.
        assertThat(store.rowThread[row]).isNull()
        assertThat(store.spamRows).isEmpty()
        assertThat(store.spamEvents).isEqualTo(0)
        assertThat(logged).isEmpty()
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun aDownloadingNoticeOfASenderWithAMessagesChat_keepsNoChatAndWritesNoSpamRow() {
        store.threads[SENDER] = 20L
        store.threadsWithMessages += 20L

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/messages-chat")))

        assertThat(startedLocations).hasSize(1)
        assertThat(store.rowThread).isEmpty()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun e19_aNoticeKeptAtSaveWhoseSenderTheHistoryCheckAllows_isDownloadedAsAMessagesMms() {
        recheck = { null }

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/history", size = TWO_MIB)))

        assertThat(startedLocations).containsExactly("http://mmsc.example.com/msg/history")
        assertThat(store.spamRows).isEmpty()
        assertThat(store.spamEvents).isEqualTo(0)
        // Its row is written when the downloaded MMS is placed.
        assertThat(logged).isEmpty()
    }

    @Test
    fun e28_aNoticeTheHistoryCheckAllowsWhoseStartFails_logsOneDeliverRow() {
        recheck = { null }
        startFails = true

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/allowed-fail", size = TWO_MIB)))

        // Told as a plain failed start in Messages; no Spam row, and its one decision-log row says where it went.
        assertThat(startFailures).isEqualTo(1)
        assertThat(startFailedWith).isNull()
        assertThat(store.spamRows).isEmpty()
        assertThat(logged).containsExactly("DELIVER")
    }

    @Test
    fun e27_aSpamNoticeThatDownloads_isSavedWithoutAChat() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/no-chat")))

        assertThat(startedLocations).hasSize(1)
        assertThat(savedChatless.values).containsExactly(true)
    }

    @Test
    fun e27_aNoticeKeptAtSave_andAMessagesNotice_areSavedAsTheLibraryWould() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/kept", size = TWO_MIB)))
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.CONTACT) }
        receiver().process(app, push(notice(SENDER, "T-2", "http://mmsc.example.com/msg/messages")))

        assertThat(savedChatless.values).containsExactly(false, false)
    }

    @Test
    fun e27_aChatlessNotice_neverGetsTheSendersChat_evenWhenTheLibraryRefusesAutomaticDownload() {
        assertThat(MmsPushDownload.savesWithChat(chatless = true) { false }).isFalse()
        assertThat(MmsPushDownload.savesWithChat(chatless = true) { true }).isFalse()
        // As in the library otherwise: the chat is made only when the MMS does not download by itself.
        assertThat(MmsPushDownload.savesWithChat(chatless = false) { false }).isTrue()
        assertThat(MmsPushDownload.savesWithChat(chatless = false) { true }).isFalse()
    }

    @Test
    fun aBlankSenderNoticeKeptAtSave_isToldAsAFailedStart() {
        val blank = SpamNotice(" ", SpamCause.UNKNOWN, downloads = false)

        download().handle(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/blank", size = TWO_MIB)), blank)

        assertThat(startFailures).isEqualTo(1)
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aNoticeKeptAtSave_logsOneBlockRow_aRepeatLogsNone() {
        val receiver = receiver()
        val data = notice(SENDER, "T-1", "http://mmsc.example.com/msg/log", size = TWO_MIB)

        receiver.process(app, push(data))
        receiver.process(app, push(data))

        assertThat(logged).containsExactly("BLOCK")
    }

    @Test
    fun aNoticeKeptAtSaveThatEndsInMessages_logsDeliver() {
        store.failSpamRow = true

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/deliver", size = TWO_MIB)))

        assertThat(logged).containsExactly("DELIVER")
        assertThat(store.rowSilent[saved.keys.single()]).isFalse()
    }

    @Test
    fun e16_aNoticeKeptAtSaveThatCannotGetAnyChat_tellsTheUserAsAFailedStart() {
        store.failThreads = true

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/nochat", size = TWO_MIB)))

        assertThat(startFailures).isEqualTo(1)
        assertThat(startFailedWith).isNull()
        assertThat(logged).containsExactly("DELIVER")
    }

    @Test
    fun aBlockedSendersNotice_isDownloadedToo_asAGroupMmsCannotBeToldApartBefore() {
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.BLOCK_RULE, 4L) }

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/blocked")))

        assertThat(startedLocations).containsExactly("http://mmsc.example.com/msg/blocked")
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun aNoticeOverOneMib_staysATapToDownloadRowInItsSpamChat() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/big", size = TWO_MIB)))

        assertThatItIsAReadRowInTheSpamChat(SpamCause.UNKNOWN)
    }

    @Test
    fun h6_aNoticeOfUnknownSize_isNotDownloaded() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/nosize", size = 0L)))

        assertThatItIsAReadRowInTheSpamChat(SpamCause.UNKNOWN)
    }

    @Test
    fun anExpiredNotice_isNotDownloaded() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/old", expired = true)))

        assertThatItIsAReadRowInTheSpamChat(SpamCause.UNKNOWN)
    }

    @Test
    fun whileRoaming_aSpamNoticeIsNotDownloaded() {
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.BLOCK_RULE, 4L) }

        receiver(isRoaming = true).process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/roam")))

        assertThatItIsAReadRowInTheSpamChat(SpamCause.BLOCKED)
    }

    @Test
    fun anAllowedNotice_isTheH8DownloadUnchanged() {
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.CONTACT) }

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/allowed")))

        assertThat(startedLocations).containsExactly("http://mmsc.example.com/msg/allowed")
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun aSpamNoticeWhoseDownloadCannotStart_isReportedAsSpam() {
        startFails = true

        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/refused")))

        val expected =
            SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = true, reason = "UNKNOWN_SENDER", subscriptionId = 1)
        assertThat(startFailedWith).isEqualTo(expected)
    }

    @Test
    fun aSpamNoticeThatCannotBeSaved_isLeftToTheLibrary() {
        val failing = MmsPushDownload(
            isDuplicate = { _, _ -> false },
            persist = { _, _, _, _ -> null },
            incoming = { IncomingMms(store) },
        )
        val spam = SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = false)

        assertThat(failing.handle(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/x")), spam))
            .isFalse()
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun mP5_withTheRealStoreQuery_aSecondDifferentNoticeIsKept_andARepeatIsNot() {
        val provider = FakeMmsProvider.install()
        val download = MmsPushDownload(
            persist = { _, ind: NotificationInd, _, _ ->
                val location = String(ind.contentLocation)
                provider.addNotice(SENDER, transactionId = String(ind.transactionId), location = location)
                    .also { saved[it] = String(ind.contentLocation) to String(ind.transactionId) }
            },
            lookup = { _, uri -> saved.getValue(uri) },
            start = { _, location, _, _, _ -> startedLocations += location },
            tracker = MmsDownloadTracker(),
            incoming = { IncomingMms(store) },
            recheck = { _, spam -> spam.cause },
        )
        val receiver = MmsPushReceiver().apply {
            pushDownload = download
            roaming = { _: Context, _: Int -> false }
        }

        receiver.process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/real-a")))
        receiver.process(app, push(notice(SENDER, "T-2", "http://mmsc.example.com/msg/real-b")))
        receiver.process(app, push(notice(SENDER, "T-2", "http://mmsc.example.com/msg/real-b")))

        assertThat(saved).hasSize(2)
        assertThat(startedLocations)
            .containsExactly("http://mmsc.example.com/msg/real-a", "http://mmsc.example.com/msg/real-b").inOrder()
    }

    // reportStartFailed (E-2, E-3)

    private var posted = 0
    private val savedNotice = Uri.parse("content://mms/inbox/42")
    private val spamNotice = SpamNotice(SENDER, SpamCause.BLOCKED, downloads = true, ruleId = 4L)

    private fun reportStartFailed(spam: SpamNotice? = spamNotice, cause: SpamCause? = spam?.cause) =
        MmsPushDownload.reportStartFailed(
            app, savedNotice, SENDER, spam, store = { store }, notify = { posted++ }, recheck = { cause },
        )

    @Test
    fun aFailedStartOfANoticeAlreadyInItsSpamChat_postsNothingAndIsNotCountedAgain() {
        store.notices[savedNotice] = NoticeRow(threadId = 9L, sender = SENDER, read = true)
        store.marks[savedNotice] = NoticeMark.Counted(9L)
        store.spamRows[9L] = Triple(SENDER, SpamCause.BLOCKED, 1)

        reportStartFailed()

        assertThat(posted).isEqualTo(0)
        assertThat(store.spamRows[9L]?.third).isEqualTo(1)
        assertThat(store.rowThread).isEmpty()
    }

    @Test
    fun aFailedStartOfAParkedNoticeInItsSpamChat_isCountedNowAndPostsNothing() {
        store.notices[savedNotice] = NoticeRow(threadId = 9L, sender = SENDER, read = false)
        store.threads[SENDER] = 9L
        store.spamRows[9L] = Triple(SENDER, SpamCause.BLOCKED, 1)

        reportStartFailed()

        assertThat(posted).isEqualTo(0)
        assertThat(store.spamRows[9L]?.third).isEqualTo(2)
        assertThat(store.rowSilent[savedNotice]).isTrue()
        // Counted now, once: the rule and the Spam event too (E-22).
        assertThat(store.ruleHits).containsExactly(4L)
        assertThat(store.spamEvents).isEqualTo(1)
    }

    @Test
    fun e19_aFailedStartWhoseSenderTheHistoryCheckAllows_isUnreadInMessagesAndTellsTheUser() {
        store.notices[savedNotice] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)
        store.rowSilent[savedNotice] = true

        reportStartFailed(cause = null)

        assertThat(posted).isEqualTo(1)
        assertThat(store.rowSilent[savedNotice]).isFalse()
        assertThat(store.spamRows).isEmpty()
    }

    @Test
    fun aFailedStartOfASpamNoticeWithoutAChat_getsItsSpamChatAndPostsNothing() {
        store.notices[savedNotice] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)

        reportStartFailed()

        assertThat(posted).isEqualTo(0)
        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[savedNotice]).isEqualTo(thread)
        assertThat(store.rowSilent[savedNotice]).isTrue()
        assertThat(store.spamRows[thread]?.second).isEqualTo(SpamCause.BLOCKED)
    }

    @Test
    fun aFailedStartWhoseSpamRowFails_showsTheQuietedNoticeInMessagesUnreadAndTellsTheUser() {
        store.notices[savedNotice] = NoticeRow(threadId = FakeMmsSpamStore.NO_CHAT, sender = SENDER)
        store.rowSilent[savedNotice] = true
        store.failSpamRow = true

        reportStartFailed()

        assertThat(posted).isEqualTo(1)
        assertThat(store.rowThread[savedNotice]).isEqualTo(store.threads.getValue(SENDER))
        assertThat(store.rowSilent[savedNotice]).isFalse()
    }

    @Test
    fun aFailedStartWhoseStoreCannotBeRead_tellsTheUser() {
        store.failThreads = true

        reportStartFailed()

        assertThat(posted).isEqualTo(1)
    }

    @Test
    fun aFailedStartInMessages_tellsTheUser() {
        reportStartFailed(spam = null)

        assertThat(posted).isEqualTo(1)
        assertThat(store.calls).isEmpty()
    }

    private fun assertThatItIsAReadRowInTheSpamChat(cause: SpamCause) {
        val row = saved.keys.single()
        assertThat(startedLocations).isEmpty()
        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[row]).isEqualTo(thread)
        assertThat(store.rowSilent[row]).isTrue()
        assertThat(store.spamRows[thread]).isEqualTo(Triple(SENDER, cause, 1))
        // Its place is final: it counts like a Spam MMS.
        assertThat(store.spamEvents).isEqualTo(1)
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    private fun push(data: ByteArray) = Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
        .setType(MmsPushDownload.MMS_MIME_TYPE)
        .putExtra("data", data)
        .putExtra("subscription", 1)

    /** An m-notification-ind; [size] 0 is "unknown", [expired] gives an absolute expiry in the past. */
    private fun notice(
        from: String,
        transactionId: String,
        location: String,
        size: Long = ONE_KIB,
        expired: Boolean = false,
    ): ByteArray {
        val o = ByteArrayOutputStream()
        fun bytes(vararg b: Int) = b.forEach { o.write(it) }
        fun text(s: String) {
            o.write(s.toByteArray(Charsets.ISO_8859_1)); o.write(0)
        }
        bytes(0x8C, 0x82)
        bytes(0x98); text(transactionId)
        bytes(0x8D, 0x90)
        val enc = from.toByteArray(Charsets.ISO_8859_1)
        bytes(0x89, enc.size + 2, 0x80); o.write(enc); o.write(0)
        bytes(0x8A, 0x80)
        // X-Mms-Message-Size, a Long-integer: its length, then the value big-endian (at least one octet).
        val digits = generateSequence(size) { it shr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toInt() }
            .toList().reversed().ifEmpty { listOf(0) }
        bytes(0x8E, digits.size); digits.forEach { o.write(it) }
        // Expiry: absolute, 1970-01-12 (long gone); or relative, 65535 s from now.
        if (expired) bytes(0x88, 0x05, 0x80, 0x03, 0x0F, 0x42, 0x40) else bytes(0x88, 0x04, 0x81, 0x02, 0xFF, 0xFF)
        bytes(0x83); text(location)
        return o.toByteArray()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val ONE_KIB = 1_024L
        const val TWO_MIB = 2_097_152L
    }
}
