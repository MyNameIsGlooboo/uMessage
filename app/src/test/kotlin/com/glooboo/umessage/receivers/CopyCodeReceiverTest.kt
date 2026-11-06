package com.glooboo.umessage.receivers

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** N6: the old "Copy code" broadcast still works for notifications posted before CopyCodeActivity. */
@RunWith(AndroidJUnit4::class)
class CopyCodeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun copied() = clipboard.primaryClip!!.getItemAt(0).text.toString()

    @Test
    fun copiesTheCodeAsSensitiveWithoutOpeningTheApp() {
        CopyCodeReceiver().onReceive(context, CopyCodeReceiver.intent(context, "482913"))

        assertThat(copied()).isEqualTo("482913")
        assertThat(clipboard.primaryClip!!.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity).isNull()
        // Stock Android 13+ shows its own clipboard confirmation.
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    @Config(sdk = [31])
    fun showsAToastBeforeAndroid13() {
        CopyCodeReceiver().onReceive(context, CopyCodeReceiver.intent(context, "482913"))

        assertThat(copied()).isEqualTo("482913")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.onb_notif_code_copied))
    }

    @Test
    fun ignoresOtherActionsAndEmptyCodes() {
        clipboard.setPrimaryClip(ClipData.newPlainText("x", "before"))
        CopyCodeReceiver().onReceive(context, Intent("other").putExtra(CopyCodeReceiver.EXTRA_CODE, "1"))
        CopyCodeReceiver().onReceive(context, CopyCodeReceiver.intent(context, ""))

        assertThat(copied()).isEqualTo("before")
    }
}
