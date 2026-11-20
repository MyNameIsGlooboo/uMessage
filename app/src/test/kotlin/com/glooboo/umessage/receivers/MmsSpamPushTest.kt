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
 * the notice is saved in the phone's store, read and seen, and downloaded like any MMS within the limits (size known,
 * at most 1 MiB, not expired, not roaming), also from a blocked sender (answer 11). Otherwise it is a "Tap to download"
 * row in the sender's Spam chat. The store is replaced by stubs (Robolectric has no MMS provider).
 */
@RunWith(AndroidJUnit4::class)
class MmsSpamPushTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private val store = FakeMmsSpamStore()

    /** Saved notice rows: uri -> (content location, transaction id). */
    private val saved = linkedMapOf<Uri, Pair<String, String>>()
    private val startedLocations = mutableListOf<String>()
    private var startFailedWith: SpamNotice? = null
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

    /** A push download on stubs that behave like the provider: a repeat is found by transaction id or location. */
    private fun download() = MmsPushDownload(
        isDuplicate = { _, ind -> saved.values.any { it.second == String(ind.transactionId) } },
        persist = { _, ind: NotificationInd, _ ->
            Uri.parse("content://mms/inbox/${saved.size + 40}").also {
                saved[it] = String(ind.contentLocation) to String(ind.transactionId)
            }
        },
        lookup = { _, uri -> saved.getValue(uri) },
        start = { _, location, _, _, _ ->
            check(!startFails) { "system refused" }
            startedLocations += location
        },
        onStartFailed = { _, _, _, spam -> startFailedWith = spam },
        tracker = MmsDownloadTracker(),
        incoming = { IncomingMms(store) },
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
    fun aSpamNoticeWithinTheLimits_isSavedReadAndDownloadedIntoTheStore_notIntoBlocked() {
        receiver().process(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/in")))

        val row = saved.keys.single()
        assertThat(startedLocations).containsExactly("http://mmsc.example.com/msg/in")
        assertThat(store.rowSilent[row]).isTrue()
        // It has no chat until the download ends: the downloaded MMS is placed then.
        assertThat(store.rowThread).isEmpty()
        assertThat(store.spamRows).isEmpty()
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
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

        assertThat(startFailedWith).isEqualTo(SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = true))
    }

    @Test
    fun aSpamNoticeThatCannotBeSaved_isLeftToTheLibrary() {
        val failing = MmsPushDownload(
            isDuplicate = { _, _ -> false },
            persist = { _, _, _ -> null },
            incoming = { IncomingMms(store) },
        )
        val spam = SpamNotice(SENDER, SpamCause.UNKNOWN, downloads = false)

        assertThat(failing.handle(app, push(notice(SENDER, "T-1", "http://mmsc.example.com/msg/x")), spam))
            .isFalse()
        assertThat(store.calls).isEmpty()
    }

    private fun assertThatItIsAReadRowInTheSpamChat(cause: SpamCause) {
        val row = saved.keys.single()
        assertThat(startedLocations).isEmpty()
        val thread = store.threads.getValue(SENDER)
        assertThat(store.rowThread[row]).isEqualTo(thread)
        assertThat(store.rowSilent[row]).isTrue()
        assertThat(store.spamRows[thread]).isEqualTo(Triple(SENDER, cause, 1))
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
