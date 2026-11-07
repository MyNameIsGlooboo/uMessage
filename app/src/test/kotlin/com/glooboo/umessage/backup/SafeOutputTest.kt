package com.glooboo.umessage.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * The 0-byte backup: the destination used to be truncated before any data existed, so a backup that failed, was
 * stopped or found nothing left an empty file that looked like a backup.
 */
@RunWith(AndroidJUnit4::class)
class SafeOutputTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun destination(content: String? = null): Pair<File, Uri> {
        val file = File.createTempFile("picked", ".json", context.cacheDir)
        if (content != null) file.writeText(content) else file.writeBytes(ByteArray(0))
        return file to Uri.fromFile(file)
    }

    @Test
    fun theBackupIsCopiedWhole() {
        val (file, uri) = destination()

        val result = SafeOutput.write(context, uri) { it.write("[1,2,3]".toByteArray()); 3 }

        assertThat(result.copied).isTrue()
        assertThat(file.readText()).isEqualTo("[1,2,3]")
        assertThat(result.bytes).isEqualTo(7)
    }

    @Test
    fun aFailingProducerNeverTouchesTheDestination() {
        val (file, uri) = destination("old backup")

        assertThrows(IllegalStateException::class.java) {
            SafeOutput.write<Unit>(context, uri) { it.write("half".toByteArray()); error("query failed") }
        }

        assertThat(file.readText()).isEqualTo("old backup")
    }

    @Test
    fun anEmptyResultIsAnErrorAndNotAnEmptyFile() {
        val (file, uri) = destination("old backup")

        assertThrows(IOException::class.java) { SafeOutput.write<Unit>(context, uri) { } }

        assertThat(file.readText()).isEqualTo("old backup")
    }

    @Test
    fun aStoppedOrEmptyRunIsNotCopied() {
        val (file, uri) = destination("old backup")

        val result = SafeOutput.write(context, uri, keep = { _: Int -> false }) {
            it.write("[]".toByteArray())
            0
        }

        assertThat(result.copied).isFalse()
        assertThat(file.readText()).isEqualTo("old backup")
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val (_, uri) = destination()
        val before = context.cacheDir.listFiles { f -> f.name.startsWith("backup") }.orEmpty().size

        runCatching { SafeOutput.write<Unit>(context, uri) { error("boom") } }
        SafeOutput.write(context, uri) { it.write(1) }

        val after = context.cacheDir.listFiles { f -> f.name.startsWith("backup") }.orEmpty().size
        assertThat(after).isEqualTo(before)
    }
}
