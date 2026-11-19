package com.glooboo.umessage.ui.thread

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.SensitiveClip
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** M-SEC5: a copy chip puts the code on the clipboard once, under a label and a toast that never show the code. */
@RunWith(AndroidJUnit4::class)
class CopyChipValueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun theCodeIsCopiedSensitiveUnderAGenericLabelAndTheToastDoesNotShowIt() {
        copyChipValue(context, "482913", sdkInt = 30)
        shadowOf(Looper.getMainLooper()).idle()

        val clip = clipboard.primaryClip!!
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("482913")
        assertThat(clip.description.label.toString()).isEqualTo(SensitiveClip.genericLabel(context))
        assertThat(clip.description.label.toString()).doesNotContain("482913")
        assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast().toString()).doesNotContain("482913")
    }

    @Test
    fun markNeverUsesTheTextAsTheLabel() {
        SensitiveClip.mark(context, "hunter2")

        assertThat(clipboard.primaryClip!!.description.label.toString()).doesNotContain("hunter2")
    }
}
