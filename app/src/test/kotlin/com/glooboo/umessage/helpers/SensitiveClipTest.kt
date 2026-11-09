package com.glooboo.umessage.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** I15, I25: a copy is marked sensitive, and the app never clears the clipboard by itself. */
@RunWith(AndroidJUnit4::class)
class SensitiveClipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    private fun copied() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun aCopiedCodeStaysOnTheClipboard() {
        assertThat(SensitiveClip.copy(context, "code", "482913")).isTrue()

        // Nothing wipes it later, however long the app runs.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertThat(copied()).isEqualTo("482913")
    }

    @Test
    fun markKeepsTheTextAndNeverClearsIt() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "milk and eggs"))

        SensitiveClip.mark(context, "milk and eggs")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))

        assertThat(copied()).isEqualTo("milk and eggs")
    }

    @Test
    fun theClipIsMarkedSensitiveOnAndroid13Plus() {
        val clip = SensitiveClip.clipOf("code", "482913", sdkInt = 33)
        assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
        assertThat(SensitiveClip.clipOf("code", "482913", sdkInt = 30).description.extras).isNull()
    }
}
