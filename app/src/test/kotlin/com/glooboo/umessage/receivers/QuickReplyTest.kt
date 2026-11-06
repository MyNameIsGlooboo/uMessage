package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.services.HeadlessSmsSendService
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** Sends that start outside the app: a reply typed into a notification, and another app's "respond via message". */
@RunWith(AndroidJUnit4::class)
class QuickReplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notSentTitle get() = context.getString(R.string.message_not_sent_short)
    private val notSentNotifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications
            .filter { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == notSentTitle }

    @Before
    fun setUp() = ShadowRecordingSmsManager.reset()

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun groupReplyGoesToEveryone() {
        assertThat(replyRecipients(listOf("111", "222", "111"), "222")).containsExactly("111", "222").inOrder()
    }

    @Test
    fun oneToOneReplyGoesToTheSender() {
        assertThat(replyRecipients(listOf("111"), "111")).containsExactly("111")
    }

    @Test
    fun unreadableThreadFallsBackToTheSender() {
        assertThat(replyRecipients(emptyList(), "111")).containsExactly("111")
    }

    @Test
    fun replyFromAGroupNotificationReachesTheWholeGroup() {
        // RM-30: Sara wrote in the family group; "Me!" must reach Sara and Reza, not Sara alone.
        FakeThreadStore.install().addGroupThread(50L, listOf("111", "222"))
        FakeSmsMmsProvider.installSms()

        offMainThread { DirectReplyReceiver().handleReply(context, "111", 50L, "Me!") }
        ShadowLooper.idleMainLooper()

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "222")
        assertThat(notSentNotifications).isEmpty()
    }

    @Test
    fun failedQuickReplyPostsANotSentNotification() {
        FakeSmsMmsProvider.installSms()
        ShadowRecordingSmsManager.failingDestinations.add("111")

        offMainThread { DirectReplyReceiver().handleReply(context, "111", 0L, "Me!") }
        ShadowLooper.idleMainLooper()

        assertThat(notSentNotifications).hasSize(1)
    }

    @Test
    fun failedRespondViaMessagePostsANotSentNotification() {
        FakeSmsMmsProvider.installSms()
        ShadowRecordingSmsManager.failingDestinations.add("111")
        val service = Robolectric.buildService(HeadlessSmsSendService::class.java).create().get()

        offMainThread { service.send("Can't talk now", listOf("111")) }
        ShadowLooper.idleMainLooper()

        assertThat(notSentNotifications).hasSize(1)
    }
}
