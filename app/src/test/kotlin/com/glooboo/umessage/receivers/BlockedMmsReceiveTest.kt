package com.glooboo.umessage.receivers

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.AndroidSpamPlatform
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.mms.BlockedMmsDownloader
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.glooboo.umessage.testing.MmsPduFixtures
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSmsManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SE-29: a blocked MMS is kept (downloaded into app-private storage, never into the inbox) so Not spam can restore
 * it with its pictures, on both blocking paths: the push gate (sender blocked before download) and the content filter
 * (blocked after mmslib downloaded it).
 */
@RunWith(AndroidJUnit4::class)
class BlockedMmsReceiveTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    @Before
    fun setUp() {
        db = SpamTestWiring.freshSpamDatabase(app)
        repo = DefaultSpamRepository(AndroidSpamPlatform(app), db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
    }

    @After
    fun tearDown() {
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
        BlockedMmsStore.dir(app).deleteRecursively()
    }

    private fun rows(): List<BlockedMessage> = db.spamDao().getAllBlocked().map { it.toModel() }

    @Test
    fun aSenderBlockedAtThePushIsDownloadedIntoPrivateStorageNotTheInbox() {
        MmsPushReceiver().process(app, pushIntent(notificationInd("+15551234567", "T-push-1")))

        val row = rows().single()
        assertThat(row.media).isEqualTo(MmsMedia.DOWNLOADING)
        assertThat(row.body).isEqualTo(MMS_PLACEHOLDER_BODY)
        val shadow = Shadow.extract<ShadowSmsManager>(com.klinker.android.send_message.SmsManagerFactory.createSmsManager(1))
        val download = shadow.lastDownloadedMultimediaMessageParams
        assertThat(download.locationUrl).isEqualTo(LOCATION)
        assertThat(download.contentUri.authority).isEqualTo("${app.packageName}.MmsFileProvider")
    }

    @Test
    fun aFinishedDownloadKeepsThePicturesAndTheText() {
        val id = storeDownloading()
        val file = downloadFile(id, MmsPduFixtures.retrieveConf(app, "Party photo", MmsPduFixtures.png()))

        BlockedMmsDownloadReceiver.handle(app, resultIntent(id, file), Activity.RESULT_OK)

        val row = rows().single()
        assertThat(row.media).isEqualTo(MmsMedia.STORED)
        assertThat(row.body).isEqualTo("Party photo")
        assertThat(BlockedMmsStore.pduFile(app, row.mediaFile!!)!!.isFile).isTrue()
        assertThat(BlockedMmsStore.thumbnailFile(app, row.mediaFile!!)!!.isFile).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun aFailedDownloadIsRecordedAsMissing() {
        val id = storeDownloading()
        val file = downloadFile(id, byteArrayOf())

        BlockedMmsDownloadReceiver.handle(app, resultIntent(id, file), SmsManager.MMS_ERROR_HTTP_FAILURE)

        val row = rows().single()
        assertThat(row.media).isEqualTo(MmsMedia.MISSING)
        assertThat(row.mediaFile).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun aDownloadForAMessageAlreadyGoneKeepsNothing() {
        val id = storeDownloading()
        runBlocking { repo.deleteBlocked(listOf(id)) }
        val file = downloadFile(id, MmsPduFixtures.retrieveConf(app, "late"))

        BlockedMmsDownloadReceiver.handle(app, resultIntent(id, file), Activity.RESULT_OK)

        assertThat(BlockedMmsStore.dir(app).listFiles().orEmpty()).isEmpty()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun aResultIntentCannotPointOutsideTheCache() {
        val id = storeDownloading()
        val outside = File(app.filesDir, "blocked-mms-elsewhere.dat").apply { writeBytes(byteArrayOf(1)) }
        BlockedMmsDownloadReceiver.handle(app, resultIntent(id, outside), Activity.RESULT_OK)
        assertThat(outside.exists()).isTrue()
        assertThat(rows().single().media).isEqualTo(MmsMedia.MISSING)
    }

    /** Content filter path: mmslib downloaded it, the text is blocked, and the PDU is kept instead of dropped. */
    @Test
    fun anMmsBlockedForItsContentIsKeptWithItsPictures() {
        val libraryFile = File(app.cacheDir, "download.123.dat")
            .apply { writeBytes(MmsPduFixtures.retrieveConf(app, "WIN A PRIZE", MmsPduFixtures.png())) }
        val handler = MmsDownloadHandler().apply { pduPath = libraryFile.path }

        handler.isAddressBlocked(app, "+15551234567/TYPE=PLMN")
        assertThat(handler.isContentBlocked(app, "WIN A PRIZE")).isTrue()

        val row = rows().single()
        assertThat(row.media).isEqualTo(MmsMedia.STORED)
        assertThat(row.body).isEqualTo("WIN A PRIZE")
        assertThat(row.hasStoredMedia).isTrue()
        assertThat(libraryFile.exists()).isFalse()
    }

    @Test
    fun anMmsBlockedForItsContentWithoutAFileStillLandsInBlocked() {
        val handler = MmsDownloadHandler().apply { pduPath = File(app.cacheDir, "missing.dat").path }
        handler.isAddressBlocked(app, "+15551234567")
        assertThat(handler.isContentBlocked(app, "spam text")).isTrue()
        val row = rows().single()
        assertThat(row.media).isEqualTo(MmsMedia.MISSING)
        assertThat(row.body).isEqualTo("spam text")
    }

    @Test
    fun autoPurgeDeletesTheKeptPictures() {
        val id = storeDownloading(receivedAt = 1_000L)
        val file = downloadFile(id, MmsPduFixtures.retrieveConf(app, "old", MmsPduFixtures.png()))
        BlockedMmsDownloadReceiver.handle(app, resultIntent(id, file), Activity.RESULT_OK)
        val name = rows().single().mediaFile!!

        runBlocking { repo.purgeExpired() }

        assertThat(rows()).isEmpty()
        assertThat(BlockedMmsStore.pduFile(app, name)!!.exists()).isFalse()
        assertThat(BlockedMmsStore.thumbnailFile(app, name)!!.exists()).isFalse()
    }

    /** A downgrade backup may still name kept files the live database does not: the sweep leaves them alone. */
    @Test
    fun sweepKeepsFilesWhileADowngradeBackupWaitsToBeMerged() {
        val name = BlockedMmsStore.save(app, MmsPduFixtures.retrieveConf(app, "kept by the backup"))!!
        BlockedMmsStore.pduFile(app, name)!!.setLastModified(1_000L)
        val backup = File(app.getDatabasePath("spamless.db").path + ".v3-backup")
        backup.parentFile!!.mkdirs()
        backup.writeBytes(byteArrayOf(0))
        try {
            AndroidSpamPlatform(app).sweepMedia(emptySet())
            assertThat(BlockedMmsStore.pduFile(app, name)!!.exists()).isTrue()
        } finally {
            backup.delete()
        }
        AndroidSpamPlatform(app).sweepMedia(emptySet())
        assertThat(BlockedMmsStore.pduFile(app, name)!!.exists()).isFalse()
    }

    private fun storeDownloading(receivedAt: Long = System.currentTimeMillis()): Long = runBlocking {
        repo.storeBlocked(
            BlockedMessage(
                address = "+15551234567", body = "", subscriptionId = 1, isMms = true, receivedAt = receivedAt,
                reason = DecisionReason.UNKNOWN_SENDER, media = MmsMedia.DOWNLOADING,
            )
        )
    }

    private fun downloadFile(id: Long, bytes: ByteArray) =
        File(app.cacheDir, "blocked-mms-$id-test.dat").apply { writeBytes(bytes) }

    private fun resultIntent(id: Long, file: File) = Intent(app, BlockedMmsDownloadReceiver::class.java)
        .putExtra(BlockedMmsDownloader.EXTRA_BLOCKED_ID, id)
        .putExtra(BlockedMmsDownloader.EXTRA_FILE, file.path)

    private fun pushIntent(data: ByteArray) = Intent("android.provider.Telephony.WAP_PUSH_DELIVER")
        .putExtra("data", data)
        .putExtra("subscription", 1)

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
        bytes(0x83); text(LOCATION)
        return o.toByteArray()
    }

    private companion object {
        const val LOCATION = "http://mmsc.example.com/msg/abc"
    }
}
