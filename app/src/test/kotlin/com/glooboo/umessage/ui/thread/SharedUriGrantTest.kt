package com.glooboo.umessage.ui.thread

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedUriGrantTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sharedUrisGoIntoClipDataWithTheReadFlag() {
        val uris = listOf(
            Uri.parse("content://media/external/video/media/1"),
            Uri.parse("content://media/external/video/media/2"),
        )
        val intent = Intent().apply { grantReadOf(uris) }

        val clip = intent.clipData!!
        assertThat(clip.itemCount).isEqualTo(2)
        assertThat(clip.getItemAt(0).uri).isEqualTo(uris[0])
        assertThat(clip.getItemAt(1).uri).isEqualTo(uris[1])
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun noUrisLeavesTheIntentAlone() {
        val intent = Intent().apply { grantReadOf(emptyList()) }

        assertThat(intent.clipData).isNull()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun aUriTheAppHasNoReadRightOnIsNotGranted() {
        val readable = Uri.parse("content://media/external/video/media/1")
        val unreadable = Uri.parse("content://com.example.nobody/files/1")

        val kept = context.readableByApp(listOf(readable, unreadable), isReadable = { it == readable })

        assertThat(kept).containsExactly(readable)
        // Nothing readable: the intent carries no grant, so startActivity cannot throw because of it.
        val intent = Intent().apply { grantReadOf(context.readableByApp(listOf(unreadable), isReadable = { false })) }
        assertThat(intent.clipData).isNull()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun copyKeepsTheContentAndOnlyTheExtension() {
        val dir = context.cacheDir.resolve("shared_test").apply { mkdirs() }
        val source = File(dir, "source.bin").apply { writeBytes(ByteArray(1000) { it.toByte() }) }

        val result = copySharedStream(context, Uri.fromFile(source), "My Holiday.mp4", dir, maxBytes = 1000)

        val copy = (result as SharedCopy.Copied).file
        assertThat(copy.name).endsWith(".mp4")
        assertThat(copy.name).doesNotContain("Holiday")
        assertThat(copy.readBytes()).isEqualTo(source.readBytes())
    }

    @Test
    fun aFileOverTheCapIsNotKept() {
        val dir = context.cacheDir.resolve("shared_test_big").apply { mkdirs() }
        val source = File(dir, "source.bin").apply { writeBytes(ByteArray(1000)) }

        val result = copySharedStream(context, Uri.fromFile(source), "a.pdf", dir, maxBytes = 999)

        assertThat(result).isEqualTo(SharedCopy.TooLarge)
        assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly("source.bin")
    }

    @Test
    fun aFolderThatCannotTakeTheCopyEndsAsAFailedCopy() {
        val base = context.cacheDir.resolve("shared_test_gone").apply { mkdirs() }
        val source = File(base, "source.bin").apply { writeBytes(ByteArray(10)) }
        val notAFolder = File(base, "plain_file").apply { writeBytes(ByteArray(1)) }

        val result = copySharedStream(context, Uri.fromFile(source), "a.pdf", notAFolder, maxBytes = 1000)

        assertThat(result).isEqualTo(SharedCopy.Failed)
    }

    @Test
    fun theCopyLimitNeverPassesTheHardCap() {
        assertThat(sharedCopyLimit(FILE_SIZE_NONE, limited = true)).isEqualTo(MAX_SHARED_COPY_BYTES)
        assertThat(sharedCopyLimit(Long.MAX_VALUE, limited = true)).isEqualTo(MAX_SHARED_COPY_BYTES)
        assertThat(sharedCopyLimit(300_000L, limited = true)).isEqualTo(300_000L)
        assertThat(sharedCopyLimit(300_000L, limited = false)).isEqualTo(MAX_SHARED_COPY_BYTES)
    }
}
