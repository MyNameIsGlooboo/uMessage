package com.glooboo.umessage.helpers

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** N8: a chat whose own channel is gone posts on the normal channel instead of into nothing. */
@RunWith(AndroidJUnit4::class)
class CustomChannelFallbackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted get() = shadowOf(manager).allNotifications

    @After
    fun tearDown() {
        resetSharedMessagesDatabase()
    }

    private fun post(body: String = "See you at noon") {
        offMainThread {
            NotificationHelper(app).postMessageNotification(
                messageId = 1L, isMms = false, address = "+15550001234", body = body, threadId = THREAD,
                bitmap = null, sender = "Alex",
            )
        }
    }

    private fun createOwnChannel() {
        manager.createNotificationChannel(
            NotificationChannel(THREAD.toString(), "Alex", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    @Test
    fun ownChannelIsUsedWhenItExists() {
        app.config.addCustomNotificationsByThreadId(THREAD)
        createOwnChannel()

        post()

        assertThat(posted.single().channelId).isEqualTo(THREAD.toString())
        assertThat(app.config.customNotifications).contains(THREAD.toString())
    }

    @Test
    fun missingOwnChannelFallsBackToTheNormalChannel() {
        app.config.addCustomNotificationsByThreadId(THREAD)

        post()

        assertThat(posted.single().channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
        assertThat(manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)).isNotNull()
    }

    @Test
    fun missingOwnChannelWithACodeUsesTheCodeChannel() {
        app.config.addCustomNotificationsByThreadId(THREAD)

        post(body = "Your verification code is 482913")

        assertThat(posted.single().channelId).isEqualTo(CODE_CHANNEL_ID)
    }

    @Test
    fun staleIdIsForgotten() {
        app.config.addCustomNotificationsByThreadId(THREAD)

        post()

        assertThat(app.config.customNotifications).doesNotContain(THREAD.toString())
    }

    @Test
    fun sendFailedNoticeFallsBackToo() {
        app.config.addCustomNotificationsByThreadId(THREAD)

        NotificationHelper(app).showSendingFailedNotification("Alex", THREAD)

        assertThat(posted.single().channelId).isEqualTo(FAILED_SEND_CHANNEL_ID)
    }

    private companion object {
        const val THREAD = 47L
    }
}
