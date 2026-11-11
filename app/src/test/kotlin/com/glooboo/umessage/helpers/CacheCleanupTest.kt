package com.glooboo.umessage.helpers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheCleanupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deletesOnlyFilesOlderThanTheLimit() {
        val dir = tmp.newFolder("attachments")
        val old = File(dir, "old.jpg").apply { writeText("x"); setLastModified(1_000L) }
        val fresh = File(dir, "fresh.jpg").apply { writeText("x"); setLastModified(9_500L) }
        assertEquals(1, CacheCleanup.deleteOlderThan(dir, 1_000L, now = 10_000L))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun missingFolderIsFine() {
        assertEquals(0, CacheCleanup.deleteOlderThan(File(tmp.root, "none"), 1_000L, now = 10_000L))
    }

    @Test
    fun deleteQuietlyRemovesTheFileAndToleratesAMissingOne() {
        val file = tmp.newFile("export.csv")
        assertTrue(CacheCleanup.deleteQuietly(file))
        assertFalse(file.exists())
        assertTrue(CacheCleanup.deleteQuietly(file))
    }

    @Test
    fun shareCopiesLiveLongerThanASlowUploadButNotAnHour() {
        assertTrue(CacheCleanup.SHARE_COPY_LIFETIME_MS >= 10 * 60 * 1000L)
        assertTrue(CacheCleanup.SHARE_COPY_LIFETIME_MS < 60 * 60 * 1000L)
    }
}
