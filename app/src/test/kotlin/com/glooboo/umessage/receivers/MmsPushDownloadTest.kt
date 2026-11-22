package com.glooboo.umessage.receivers

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.MmsDownloadFailure
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.AndroidSpamPlatform
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.MmsReceivedReceiver
import com.klinker.android.send_message.SmsManagerFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSmsManager
import java.io.ByteArrayOutputStream

/**
 * H8: an allowed MMS is saved and its download is started with an explicit PendingIntent to the manifest receiver
 * [MmsReceiver], so the result wakes the app even when the process was frozen or killed during the download. The
 * system provider is replaced by stubs (Robolectric has no MMS provider); the system download call is the real one.
 */
@RunWith(AndroidJUnit4::class)
class MmsPushDownloadTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val saved = Uri.parse("content://mms/inbox/42")

    private var persisted = 0
    private var started = 0
    private var startFailures = 0
    private var failedFor = ""
    private var tracker = MmsDownloadTracker()

    @Before
    fun setUp() {
        persisted = 0
        started = 0
        startFailures = 0
        tracker = MmsDownloadTracker()
    }

    @After
    fun tearDown() {
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun download(
        duplicate: Boolean = false,
        persist: (Int) -> Uri? = { saved },
        start: (String, Uri, Int) -> Unit = { location, uri, sub ->
            MmsPushDownload.startDownload(app, location, "T-1", uri, sub)
        },
    ) = MmsPushDownload(
        isDuplicate = { _, _ -> duplicate },
        persist = { _, _, sub, _ -> persisted++; persist(sub) },
        lookup = { _, _ -> location to "T-1" },
        start = { _, loc, _, uri, sub -> started++; start(loc, uri, sub) },
        onStartFailed = { _, _, from, _ -> startFailures++; failedFor = from },
        tracker = tracker,
    )

    private var location = "http://mmsc.example.com/msg/a1"

    private fun pushIntent(data: ByteArray = notificationInd("+15551234567", "T-1"), subscriptionId: Int = 1) =
        Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
            .setType(MmsPushDownload.MMS_MIME_TYPE)
            .putExtra("data", data)
            .putExtra("subscription", subscriptionId)

    private fun lastDownload() =
        Shadow.extract<ShadowSmsManager>(SmsManagerFactory.createSmsManager(1)).lastDownloadedMultimediaMessageParams

    @Test
    fun anAllowedNotification_isSavedAndDownloadedThroughAnExplicitPendingIntent() {
        location = "http://mmsc.example.com/msg/explicit"

        assertThat(download().handle(app, pushIntent())).isTrue()

        assertThat(persisted).isEqualTo(1)
        val params = lastDownload()
        assertThat(params.locationUrl).isEqualTo(location)
        assertThat(params.contentUri.authority).isEqualTo("${app.packageName}.MmsFileProvider")
        val pending = shadowOf(params.downloadedIntent)
        assertThat(pending.isBroadcast).isTrue()
        val result = pending.savedIntent
        // Explicit and to a receiver the manifest declares: the system can start the app for the result.
        assertThat(result.action).isNull()
        assertThat(result.component).isEqualTo(ComponentName(app, MmsReceiver::class.java))
        assertThat(result.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH)).startsWith(app.cacheDir.path)
        assertThat(result.getStringExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL)).isEqualTo(location)
        assertThat(result.getStringExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID)).isEqualTo("T-1")
        assertThat(result.getBooleanExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, false)).isTrue()
        assertThat(result.getParcelableExtra<Uri>(MmsReceivedReceiver.EXTRA_URI)).isEqualTo(saved)
        // The SIM the push came in on is kept for the receiver.
        assertThat(result.getIntExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, -99)).isEqualTo(1)
    }

    @Test
    fun theReceiverIsDeclaredNotExported() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, MmsReceiver::class.java), 0)
        assertThat(info.exported).isFalse()
    }

    @Test
    fun thePendingIntentIsMutableForTheSystemResult() {
        val flags = MmsPushDownload.pendingFlags()
        assertThat(flags and PendingIntent.FLAG_CANCEL_CURRENT).isNotEqualTo(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertThat(flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
            assertThat(flags and PendingIntent.FLAG_IMMUTABLE).isEqualTo(0)
        }
        location = "http://mmsc.example.com/msg/flags"
        download().handle(app, pushIntent())
        assertThat(shadowOf(lastDownload().downloadedIntent).flags and PendingIntent.FLAG_MUTABLE)
            .isEqualTo(flags and PendingIntent.FLAG_MUTABLE)
    }

    @Test
    fun twoMessagesGetTheirOwnPendingIntent() {
        val first = MmsPushDownload.requestCode(Uri.parse("content://mms/inbox/42"), "a")
        val second = MmsPushDownload.requestCode(Uri.parse("content://mms/inbox/43"), "b")
        assertThat(first).isNotEqualTo(second)
        // Without a row id the location tells them apart.
        assertThat(MmsPushDownload.requestCode(Uri.parse("content://mms/inbox"), "a"))
            .isNotEqualTo(MmsPushDownload.requestCode(Uri.parse("content://mms/inbox"), "b"))
    }

    @Test
    fun aRepeatedNotificationWithTheSameTransactionIsNeitherSavedNorDownloaded() {
        assertThat(download(duplicate = true).handle(app, pushIntent())).isTrue()

        assertThat(persisted).isEqualTo(0)
        assertThat(started).isEqualTo(0)
    }

    @Test
    fun aRepeatedPushForTheSameLocationStartsOneDownload() {
        location = "http://mmsc.example.com/msg/twice"

        assertThat(download().handle(app, pushIntent())).isTrue()
        assertThat(download().handle(app, pushIntent())).isTrue()

        assertThat(started).isEqualTo(1)
    }

    @Test
    fun theSimOfThePushReachesTheSavedRowAndTheDownload() {
        location = "http://mmsc.example.com/msg/sim"
        var persistedFor = -99
        var downloadedFor = -99

        download(persist = { sub -> persistedFor = sub; saved }, start = { _, _, sub -> downloadedFor = sub })
            .handle(app, pushIntent(subscriptionId = 7))

        assertThat(persistedFor).isEqualTo(7)
        assertThat(downloadedFor).isEqualTo(7)
    }

    @Test
    fun aFailureBeforeTheNotificationIsSaved_leavesTheMessageToTheLibrary() {
        assertThat(download(persist = { error("provider busy") }).handle(app, pushIntent())).isFalse()
        assertThat(download(persist = { null }).handle(app, pushIntent())).isFalse()

        assertThat(started).isEqualTo(0)
    }

    @Test
    fun aFailedDownloadStartAfterSaving_stillCountsAsHandledNotifiesAndMarksTheRowFailed() {
        location = "http://mmsc.example.com/msg/refused"

        assertThat(download(start = { _, _, _ -> error("system refused") }).handle(app, pushIntent())).isTrue()

        assertThat(persisted).isEqualTo(1)
        assertThat(startFailures).isEqualTo(1)
        assertThat(tracker.state.value[42L]).isEqualTo(MmsDownloadTracker.Entry.Failed)
        // A tap on "Tap to try again" is not refused by the push download that never started.
        assertThat(MmsPushDownload.isStarted(location)).isFalse()
    }

    @Test
    fun theThreadIsLookedUpForTheSenderWithoutTheTypeSuffix() {
        location = "http://mmsc.example.com/msg/suffix"
        val data = notificationInd("+15551234567/TYPE=PLMN", "T-1")

        download(start = { _, _, _ -> error("system refused") }).handle(app, pushIntent(data = data))

        assertThat(failedFor).isEqualTo("+15551234567")
    }

    @Test
    fun aSystemDownloadError_clearsTheStartedLocationSoARetryIsNotRefused() {
        location = "http://mmsc.example.com/msg/error"
        download().handle(app, pushIntent())
        assertThat(MmsPushDownload.isStarted(location)).isTrue()

        // The failed result reaches MmsDownloadFailure (no notice row here, so nothing is placed).
        val result = Intent(app, MmsReceiver::class.java).putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location)
        MmsDownloadFailure(notify = {}, toast = {}, finishLibraryDownload = {}, tracker = tracker).handle(app, result)

        assertThat(MmsPushDownload.isStarted(location)).isFalse()
    }

    @Test
    fun whileThePushDownloadRuns_theRowShowsDownloadingAndATapIsRefused() {
        location = "http://mmsc.example.com/msg/tap"

        download().handle(app, pushIntent())

        assertThat(tracker.state.value[42L]).isInstanceOf(MmsDownloadTracker.Entry.Downloading::class.java)
        assertThat(tracker.begin(42L, System.currentTimeMillis())).isFalse()
        assertThat(MmsPushDownload.isStarted(location)).isTrue()
        // After the download timeout the push download no longer blocks a retry.
        assertThat(MmsPushDownload.isStarted(location, System.currentTimeMillis() + 200_000L)).isFalse()
    }

    @Test
    fun aDownloadResultThatFails_turnsTheRunningRowIntoFailed() {
        location = "http://mmsc.example.com/msg/result"
        download().handle(app, pushIntent())

        // MmsDownloadFailure does this when the failed result names no notice row.
        tracker.failAllRunning()

        assertThat(tracker.state.value[42L]).isEqualTo(MmsDownloadTracker.Entry.Failed)
    }

    @Test
    fun anythingThatIsNotAGoodNotification_isLeftToTheLibrary() {
        val handler = download()

        // Wrong action, wrong type, no data, data that is not a PDU, a PDU that is not a notification-ind.
        assertThat(handler.handle(app, pushIntent().setAction("android.intent.action.VIEW"))).isFalse()
        assertThat(handler.handle(app, pushIntent().setType("application/vnd.wap.sic"))).isFalse()
        assertThat(handler.handle(app, Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
            .setType(MmsPushDownload.MMS_MIME_TYPE))).isFalse()
        assertThat(handler.handle(app, pushIntent(data = byteArrayOf(1, 2, 3)))).isFalse()
        assertThat(handler.handle(app, pushIntent(data = deliveryInd()))).isFalse()

        assertThat(persisted).isEqualTo(0)
        assertThat(started).isEqualTo(0)
    }

    @Test
    fun thePushReceiverStartsTheDownloadItselfForAnAllowedMessage() {
        val db = SpamTestWiring.freshSpamDatabase(app)
        val repo =
            DefaultSpamRepository(AndroidSpamPlatform(app), db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.NO_MATCH) }
        location = "http://mmsc.example.com/msg/receiver"
        val receiver = MmsPushReceiver().apply { pushDownload = download() }

        receiver.process(app, pushIntent())

        assertThat(persisted).isEqualTo(1)
        assertThat(started).isEqualTo(1)
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    private fun notificationInd(from: String, transactionId: String): ByteArray {
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
        bytes(0x8E, 0x02, 0x04, 0x00)
        bytes(0x88, 0x04, 0x81, 0x02, 0xFF, 0xFF)
        bytes(0x83); text(location)
        return o.toByteArray()
    }

    /** m-delivery-ind: message type, version, message id, date, recipient, status. */
    private fun deliveryInd(): ByteArray {
        val o = ByteArrayOutputStream()
        fun bytes(vararg b: Int) = b.forEach { o.write(it) }
        bytes(0x8C, 0x86)
        bytes(0x8D, 0x90)
        bytes(0x8B); o.write("M-1".toByteArray()); o.write(0)
        bytes(0x85, 0x04, 0x00, 0x00, 0x00, 0x01)
        bytes(0x97, 0x0E, 0x80); o.write("+15550001234".toByteArray()); o.write(0)
        bytes(0x95, 0x81)
        return o.toByteArray()
    }
}
