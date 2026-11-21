package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getSmsDraft
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

    /** M-P3: a reply that never reached the provider stays as the chat's draft, and the spinner ends. */
    @Test
    fun failedQuickReplyKeepsTheTextAndEndsTheSpinner() {
        Robolectric.setupContentProvider(RefusingSmsProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val threadId = 77L
        val original = NotificationCompat.Builder(context, "test").setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle("+15550001234").setContentText("Are you coming?").build()
        manager.notify(threadId.hashCode(), original)

        offMainThread { DirectReplyReceiver().handleReply(context, "+15550001234", threadId, "On my way") }
        ShadowLooper.idleMainLooper()

        assertThat(notSentNotifications).hasSize(1)
        assertThat(context.getSmsDraft(threadId)).isEqualTo("On my way")
        val reposted = shadowOf(manager).getNotification(threadId.hashCode())
        assertThat(reposted.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(reposted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).isEqualTo("Are you coming?")
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

/** An SMS provider that saves nothing, as when the phone's message store refuses a write. */
class RefusingSmsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null
}
