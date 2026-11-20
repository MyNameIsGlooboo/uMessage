package com.glooboo.umessage.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** M-D3: plain temporary export files live in one cache folder of their own, emptied at app start. */
@RunWith(AndroidJUnit4::class)
class ExportTempFolderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aTemporaryFileGoesIntoItsOwnFolderInTheCache() {
        val file = ExportTempFolder.newFile(context, "backup", ".tmp")

        assertThat(file.parentFile).isEqualTo(File(context.cacheDir, ExportTempFolder.DIR))
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun theWipeAtStartDeletesWhatAnEarlierRunLeftButNotAFileOfThisRun() {
        val left = ExportTempFolder.newFile(context, "backup", ".tmp").apply { setLastModified(1_000_000L) }
        val current = ExportTempFolder.newFile(context, "backup", ".tmp")
        val other = File(context.cacheDir, "shared_logs").apply { mkdirs() }.resolve("keep.txt")
            .apply { writeText("x"); setLastModified(1_000_000L) }

        val wiped = ExportTempFolder.wipe(context, before = current.lastModified())

        assertThat(wiped).isEqualTo(1)
        assertThat(left.exists()).isFalse()
        assertThat(current.exists()).isTrue()
        assertThat(other.exists()).isTrue()
    }
}
