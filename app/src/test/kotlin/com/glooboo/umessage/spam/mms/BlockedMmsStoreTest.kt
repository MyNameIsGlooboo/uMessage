package com.glooboo.umessage.spam.mms

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.MmsPduFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SE-29: what is kept of a blocked MMS, and where. */
@RunWith(AndroidJUnit4::class)
class BlockedMmsStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun tearDown() {
        BlockedMmsStore.dir(app).deleteRecursively()
    }

    @Test fun parsesTheTextAndFirstPictureOfADownloadedMms() {
        val png = MmsPduFixtures.png()
        val content = BlockedMmsContent.parse(MmsPduFixtures.retrieveConf(app, "Photo from the party", png))
        assertThat(content).isNotNull()
        assertThat(content!!.text).isEqualTo("Photo from the party")
        assertThat(content.firstImage).isEqualTo(png)
    }

    @Test fun somethingThatIsNotADownloadedMmsIsNotKept() {
        assertThat(BlockedMmsContent.parse(null)).isNull()
        assertThat(BlockedMmsContent.parse(byteArrayOf(1, 2, 3))).isNull()
        assertThat(BlockedMmsStore.save(app, byteArrayOf(1, 2, 3))).isNull()
        assertThat(BlockedMmsStore.dir(app).listFiles().orEmpty()).isEmpty()
    }

    @Test fun keepsTheMmsAndAThumbnailInAppPrivateNoBackupStorage() {
        val pdu = MmsPduFixtures.retrieveConf(app, "hi", MmsPduFixtures.png())
        val name = BlockedMmsStore.save(app, pdu)!!

        val kept = BlockedMmsStore.pduFile(app, name)!!
        assertThat(kept.readBytes()).isEqualTo(pdu)
        assertThat(kept.canonicalPath).startsWith(app.noBackupFilesDir.canonicalPath)
        assertThat(BlockedMmsStore.thumbnailFile(app, name)!!.isFile).isTrue()

        BlockedMmsStore.delete(app, listOf(name))
        assertThat(kept.exists()).isFalse()
        assertThat(BlockedMmsStore.thumbnailFile(app, name)!!.exists()).isFalse()
    }

    @Test fun anMmsWithoutPicturesHasNoThumbnail() {
        val name = BlockedMmsStore.save(app, MmsPduFixtures.retrieveConf(app, "text only"))!!
        assertThat(BlockedMmsStore.pduFile(app, name)!!.isFile).isTrue()
        assertThat(BlockedMmsStore.thumbnailFile(app, name)!!.exists()).isFalse()
    }

    @Test fun namesThatAreNotOursNeverReachOutsideTheStore() {
        assertThat(BlockedMmsStore.pduFile(app, "../../databases/spamless")).isNull()
        assertThat(BlockedMmsStore.thumbnailFile(app, "")).isNull()
    }

    @Test fun sweepDeletesOldFilesNoRowRefersTo() {
        val kept = BlockedMmsStore.save(app, MmsPduFixtures.retrieveConf(app, "kept"))!!
        val orphan = BlockedMmsStore.save(app, MmsPduFixtures.retrieveConf(app, "orphan"))!!
        val fresh = BlockedMmsStore.save(app, MmsPduFixtures.retrieveConf(app, "fresh orphan"))!!
        val old = System.currentTimeMillis() - 2 * BlockedMmsStore.SWEEP_MIN_AGE_MS
        listOf(kept, orphan).forEach { BlockedMmsStore.pduFile(app, it)!!.setLastModified(old) }

        BlockedMmsStore.sweep(app, keep = setOf(kept))

        assertThat(file(kept).exists()).isTrue()
        assertThat(file(orphan).exists()).isFalse()
        assertThat(file(fresh).exists()).isTrue()
    }

    private fun file(name: String): File = BlockedMmsStore.pduFile(app, name)!!
}
