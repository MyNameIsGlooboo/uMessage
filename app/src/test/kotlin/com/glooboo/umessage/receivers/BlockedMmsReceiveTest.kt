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
import java.io.File

/**
 * SE-29: a held MMS is kept (downloaded into app-private storage, never into the inbox) so Not spam can restore it
 * with its pictures. Provider truth puts no new MMS there; these cover late results of downloads started before.
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

}
