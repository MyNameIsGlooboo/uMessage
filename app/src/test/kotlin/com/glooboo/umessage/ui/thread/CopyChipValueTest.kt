package com.glooboo.umessage.ui.thread

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.SensitiveClip
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * M-SEC5: a copy chip, and "Copy" in a hidden chat, put the text on the clipboard once, under a label and a toast that
 * never show it.
 */
@RunWith(AndroidJUnit4::class)
class CopyChipValueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val genericToast get() = context.getString(org.fossify.commons.R.string.value_copied_to_clipboard)
    private var writes = 0

    @Before
    fun setUp() {
        clipboard.addPrimaryClipChangedListener { writes++ }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertCopiedPrivately(text: String) {
        val clip = clipboard.primaryClip!!
        assertThat(writes).isEqualTo(1)
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo(text)
        assertThat(clip.description.label.toString()).isEqualTo(SensitiveClip.genericLabel(context))
        assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
    }

    @Test
    fun belowAndroid13TheCodeIsCopiedOnceWithAGenericToast() {
        copyChipValue(context, "482913", sdkInt = 30)
        idle()

        assertCopiedPrivately("482913")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast().toString()).isEqualTo(genericToast)
    }

    @Test
    fun onAndroid13AndLaterTheAppShowsNoToast() {
        copyChipValue(context, "482913", sdkInt = 33)
        idle()

        assertCopiedPrivately("482913")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun copyInAHiddenChatIsOneSensitiveWriteAndTheToastDoesNotRepeatTheText() {
        // Robolectric runs SDK 34 (no toast there); the clipboard write is what matters here.
        copySelection(context, "see you at 6", fromHiddenChat = true)
        idle()

        assertCopiedPrivately("see you at 6")
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun copyInAnOrdinaryChatStaysThePlainCopy() {
        copySelection(context, "see you at 6", fromHiddenChat = false)
        idle()

        assertThat(writes).isEqualTo(1)
        assertThat(clipboard.primaryClip!!.description.extras).isNull()
    }

    @Test
    fun markNeverUsesTheTextAsTheLabel() {
        SensitiveClip.mark(context, "hunter2")

        assertThat(clipboard.primaryClip!!.description.label.toString()).doesNotContain("hunter2")
    }
}
