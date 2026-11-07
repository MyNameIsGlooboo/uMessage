package com.glooboo.umessage.helpers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I15, I25: a copied code is cleared after a minute, but never something the user copied in the meantime. */
@RunWith(AndroidJUnit4::class)
class SensitiveClipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun aCopiedCodeIsRecognisedAsStillThere() {
        assertThat(SensitiveClip.copy(context, "code", "482913", clearAfterMs = null)).isTrue()

        assertThat(SensitiveClip.holds(clipboard.primaryClip, "482913")).isTrue()
        // The clear itself is best effort (the platform may refuse it); it must never throw.
        SensitiveClip.clearIfHolds(context, "482913")
    }

    @Test
    fun somethingCopiedLaterIsNeverWiped() {
        SensitiveClip.copy(context, "code", "482913", clearAfterMs = null)
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "milk and eggs"))

        assertThat(SensitiveClip.clearIfHolds(context, "482913")).isFalse()
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("milk and eggs")
    }

    @Test
    fun theClipIsMarkedSensitiveOnAndroid13Plus() {
        val clip = SensitiveClip.clipOf("code", "482913", sdkInt = 33)
        assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
        assertThat(SensitiveClip.clipOf("code", "482913", sdkInt = 30).description.extras).isNull()
    }

    @Test
    fun holdsComparesTheExactText() {
        assertThat(SensitiveClip.holds(ClipData.newPlainText("a", "1234"), "1234")).isTrue()
        assertThat(SensitiveClip.holds(ClipData.newPlainText("a", "12345"), "1234")).isFalse()
        assertThat(SensitiveClip.holds(null, "1234")).isFalse()
    }
}
