package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheCleanerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hour = 60 * 60 * 1000L

    private fun file(folder: String, name: String, ageHours: Long, now: Long): File {
        val dir = File(context.cacheDir, folder).apply { mkdirs() }
        return File(dir, name).apply {
            writeText("x")
            setLastModified(now - ageHours * hour)
        }
    }

    @Test
    fun deletesOldCopiesAndKeepsRecentOnes() {
        val now = System.currentTimeMillis()
        val oldExport = file("shared_logs", "chat-old.txt", 30, now)
        val oldPhoto = file("compressed", "a.jpg", 25, now)
        val oldCapture = file("attachments", "attachment_1.jpg", 48, now)
        val fresh = file("shared_logs", "chat-new.txt", 2, now)
        val freshPhoto = file("compressed", "b.jpg", 23, now)

        val deleted = CacheCleaner.clean(context.cacheDir, now)

        assertThat(deleted).isEqualTo(3)
        assertThat(oldExport.exists()).isFalse()
        assertThat(oldPhoto.exists()).isFalse()
        assertThat(oldCapture.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(freshPhoto.exists()).isTrue()
    }

    @Test
    fun leavesFilesOutsideTheShareFoldersAlone() {
        val now = System.currentTimeMillis()
        val other = file("other", "keep.bin", 100, now)

        CacheCleaner.clean(context.cacheDir, now)

        assertThat(other.exists()).isTrue()
    }

    @Test
    fun runsOnlyOncePerDay() {
        val now = System.currentTimeMillis()
        val first = file("compressed", "c.jpg", 30, now)
        CacheCleaner.runIfDue(context, now)
        assertThat(first.exists()).isFalse()

        val second = file("compressed", "d.jpg", 30, now)
        CacheCleaner.runIfDue(context, now + hour)
        assertThat(second.exists()).isTrue()

        CacheCleaner.runIfDue(context, now + 25 * hour)
        assertThat(second.exists()).isFalse()
    }
}
