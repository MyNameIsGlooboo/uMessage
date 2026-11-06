package com.glooboo.umessage.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NOTIFICATION_CHANNEL_ID
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** RM-34: blocked message notifications are detected, and the fix button opens the page that turns them back on. */
@RunWith(AndroidJUnit4::class)
class NotificationsOffTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun notificationsOnShowsNothing() {
        assertThat(context.currentNotificationsBlock()).isNull()
    }

    @Test
    fun deniedPermissionOrAppSwitchOffIsDetected() {
        shadowOf(manager).setNotificationsEnabled(false)

        val block = context.currentNotificationsBlock()

        assertThat(block).isEqualTo(NotificationsBlock.APP)
        assertThat(context.notificationsFixIntent(block!!).action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @Test
    fun messagesChannelTurnedOffIsDetected() {
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Received messages", NotificationManager.IMPORTANCE_NONE)
        )

        val block = context.currentNotificationsBlock()

        assertThat(block).isEqualTo(NotificationsBlock.CHANNEL)
        val intent = context.notificationsFixIntent(block!!)
        assertThat(intent.action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo(NOTIFICATION_CHANNEL_ID)
    }

    @Test
    fun appSwitchWinsOverTheChannel() {
        assertThat(notificationsBlock(appEnabled = false, channelImportance = NotificationManager.IMPORTANCE_NONE))
            .isEqualTo(NotificationsBlock.APP)
        assertThat(notificationsBlock(appEnabled = true, channelImportance = null)).isNull()
        assertThat(notificationsBlock(appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_HIGH)).isNull()
    }
}
