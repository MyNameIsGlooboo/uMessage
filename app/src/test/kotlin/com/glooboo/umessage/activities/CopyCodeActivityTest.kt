package com.glooboo.umessage.activities

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** N6: the notification "Copy code" action copies from an invisible screen of its own and closes at once. */
@RunWith(AndroidJUnit4::class)
class CopyCodeActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun copied() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun resumed(code: String): ActivityController<CopyCodeActivity> =
        Robolectric.buildActivity(CopyCodeActivity::class.java, CopyCodeActivity.intent(context, code))
            .create().start().resume().visible()

    @Test
    fun copiesTheCodeAsSensitiveOnceFocusedAndFinishes() {
        clipboard.setPrimaryClip(ClipData.newPlainText("x", "before"))
        val controller = resumed("482913")
        assertThat(copied()).isEqualTo("before")

        controller.windowFocusChanged(true)

        assertThat(copied()).isEqualTo("482913")
        assertThat(clipboard.primaryClip!!.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        assertThat(controller.get().isFinishing).isTrue()
        // It opens nothing else: no main screen, no lock screen.
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()
        // Stock Android 13+ shows its own clipboard confirmation.
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    fun copiesAfterAShortWaitWhenFocusNeverComes() {
        val controller = resumed("482913")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CopyCodeActivity.FOCUS_FALLBACK_MS))

        assertThat(copied()).isEqualTo("482913")
        assertThat(controller.get().isFinishing).isTrue()
    }

    @Test
    fun copiesOnlyOnce() {
        val controller = resumed("482913")
        controller.windowFocusChanged(true)
        clipboard.setPrimaryClip(ClipData.newPlainText("x", "later"))

        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertThat(copied()).isEqualTo("later")
    }

    @Test
    fun anEmptyCodeCopiesNothingAndFinishes() {
        clipboard.setPrimaryClip(ClipData.newPlainText("x", "before"))
        val controller = Robolectric.buildActivity(CopyCodeActivity::class.java, CopyCodeActivity.intent(context, ""))
            .create()

        assertThat(controller.get().isFinishing).isTrue()
        assertThat(copied()).isEqualTo("before")
    }

    @Test
    fun aXiaomiPhoneGetsAToastOnAndroid13Plus() {
        ShadowBuild.setManufacturer("Xiaomi")
        resumed("482913").windowFocusChanged(true)

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.onb_notif_code_copied))
    }

    @Test
    @Config(sdk = [31])
    fun showsAToastBeforeAndroid13() {
        resumed("482913").windowFocusChanged(true)

        assertThat(copied()).isEqualTo("482913")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.onb_notif_code_copied))
    }

    @Test
    fun theIntentStartsItsOwnTask() {
        val intent = CopyCodeActivity.intent(context, "1")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.component?.className).isEqualTo(CopyCodeActivity::class.java.name)
    }

    @Test
    fun toastRule() {
        // Below Android 13 the system never confirms a copy.
        assertThat(CopyCode.needsOwnToast(sdkInt = 32, manufacturer = "Google", brand = "google")).isTrue()
        // Stock Android 13+ does.
        assertThat(CopyCode.needsOwnToast(sdkInt = 33, manufacturer = "Google", brand = "google")).isFalse()
        assertThat(CopyCode.needsOwnToast(sdkInt = 36, manufacturer = "samsung", brand = "samsung")).isFalse()
        // MIUI / HyperOS don't, whatever the brand on the box.
        assertThat(CopyCode.needsOwnToast(sdkInt = 36, manufacturer = "Xiaomi", brand = "Xiaomi")).isTrue()
        assertThat(CopyCode.needsOwnToast(sdkInt = 34, manufacturer = "Xiaomi", brand = "Redmi")).isTrue()
        assertThat(CopyCode.needsOwnToast(sdkInt = 34, manufacturer = "Xiaomi", brand = "POCO")).isTrue()
    }
}
