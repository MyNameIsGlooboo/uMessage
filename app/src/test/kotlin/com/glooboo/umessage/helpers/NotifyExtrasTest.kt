package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** I50, I53, I27: the decisions of the three optional notification extras, with invented inputs. */
@RunWith(AndroidJUnit4::class)
class NotifyExtrasTest {

    private val wake = WakeState(
        enabled = true, quiet = false, hidden = false, alertOnlyOnce = false,
        ringerNormal = true, dndOff = true, screenOn = false,
    )
    private val bubble = BubbleState(
        enabled = true, sdk = Build.VERSION_CODES.R, appLockOn = false, hidden = false, quietOrNoReply = false,
    )

    @Test
    fun allThreeExtrasAreOffByDefault() {
        val prefs = NotifyPrefs.get(ApplicationProvider.getApplicationContext<Context>())
        assertThat(prefs.wakeScreen).isFalse()
        assertThat(prefs.chatBubbles).isFalse()
        assertThat(prefs.focusCodes).isFalse()
    }

    @Test
    fun theScreenWakesOnlyWhenEveryConditionHolds() {
        assertThat(ScreenWake.shouldWake(wake)).isTrue()
        assertThat(ScreenWake.shouldWake(wake.copy(enabled = false))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(quiet = true))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(hidden = true))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(alertOnlyOnce = true))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(ringerNormal = false))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(dndOff = false))).isFalse()
        assertThat(ScreenWake.shouldWake(wake.copy(screenOn = true))).isFalse()
    }

    @Test
    fun bubblesNeedTheSwitchAndAndroid11() {
        assertThat(BubbleSupport.allowed(bubble)).isTrue()
        assertThat(BubbleSupport.allowed(bubble.copy(enabled = false))).isFalse()
        assertThat(BubbleSupport.allowed(bubble.copy(sdk = Build.VERSION_CODES.Q))).isFalse()
        assertThat(BubbleSupport.supported(Build.VERSION_CODES.Q)).isFalse()
        assertThat(BubbleSupport.supported(Build.VERSION_CODES.R)).isTrue()
    }

    @Test
    fun bubblesNeverShowBehindTheLockOrForHiddenQuietOrNoReplyChats() {
        assertThat(BubbleSupport.allowed(bubble.copy(appLockOn = true))).isFalse()
        assertThat(BubbleSupport.available(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, appLockOn = true)).isFalse()
        assertThat(BubbleSupport.allowed(bubble.copy(hidden = true))).isFalse()
        assertThat(BubbleSupport.allowed(bubble.copy(quietOrNoReply = true))).isFalse()
    }

    @Test
    fun focusExtrasApplyOnlyOnXiaomiForAVisibleCodeWhenOn() {
        assertThat(FocusNotification.applies(true, Oem.XIAOMI, hasCode = true, codeHidden = false)).isTrue()
        assertThat(FocusNotification.applies(false, Oem.XIAOMI, hasCode = true, codeHidden = false)).isFalse()
        assertThat(FocusNotification.applies(true, Oem.SAMSUNG, hasCode = true, codeHidden = false)).isFalse()
        assertThat(FocusNotification.applies(true, Oem.OTHER, hasCode = true, codeHidden = false)).isFalse()
        assertThat(FocusNotification.applies(true, Oem.XIAOMI, hasCode = false, codeHidden = false)).isFalse()
        assertThat(FocusNotification.applies(true, Oem.XIAOMI, hasCode = true, codeHidden = true)).isFalse()
    }

    @Test
    fun focusParamCarriesTheCodeAndTheSender() {
        val param = JSONObject(FocusNotification.paramJson("482913", "ExampleBank")).getJSONObject("param_v2")
        assertThat(param.getString("ticker")).isEqualTo("482913")
        val info = param.getJSONObject("baseInfo")
        assertThat(info.getString("title")).isEqualTo("482913")
        assertThat(info.getString("content")).isEqualTo("ExampleBank")
    }
}
