package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockScreenSystemSettingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyAnExplicitShowAllValueCountsAsShowingContent() {
        assertThat(LockScreenSystemSetting.showsAllContent(1)).isTrue()
        assertThat(LockScreenSystemSetting.showsAllContent(0)).isFalse()
        assertThat(LockScreenSystemSetting.showsAllContent(null)).isFalse()
    }

    @Test
    fun readsThePhonesSetting() {
        assertThat(LockScreenSystemSetting.systemShowsPrivateContent(context)).isFalse()

        Settings.Secure.putInt(context.contentResolver, "lock_screen_allow_private_notifications", 1)
        assertThat(LockScreenSystemSetting.systemShowsPrivateContent(context)).isTrue()

        Settings.Secure.putInt(context.contentResolver, "lock_screen_allow_private_notifications", 0)
        assertThat(LockScreenSystemSetting.systemShowsPrivateContent(context)).isFalse()
    }
}
