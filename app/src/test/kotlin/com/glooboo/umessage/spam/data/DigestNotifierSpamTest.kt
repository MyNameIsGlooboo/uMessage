package com.glooboo.umessage.spam.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.CleanupPrefs
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step H (section 5): the weekly summary counts the received texts of the chats in Spam that arrived
 * there in the last week, waits for the first sort, and opens the Spam tab, not the old list. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class DigestNotifierSpamTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 50 * CleanupPrefs.DIGEST_INTERVAL_MS
    private val since = now - 3 * DAY_MS

    private val notifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    @Before
    fun setUp() {
        context.cleanupPrefs.digestOn = true
        context.cleanupPrefs.claimDigest(now - CleanupPrefs.DIGEST_INTERVAL_MS)
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        offMainThread {
            context.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = SPAM, snippet = "", date = 0, read = true, title = SENDER, photoUri = "",
                    isGroupConversation = false, phoneNumber = SENDER,
                ),
            )
            context.conversationsDB.insertSpamThread(SpamThread(SPAM, SENDER, SpamCause.UNKNOWN, spamSince = since))
            listOf(
                text(1, now - DAY_MS),
                text(2, now - 2 * DAY_MS),
                // Arrived before the chat went to Spam, sent by the user, or older than a week: not counted.
                text(3, since - DAY_MS),
                text(4, now - DAY_MS, type = Telephony.Sms.MESSAGE_TYPE_SENT),
                text(5, now - 9 * DAY_MS),
            ).forEach { context.messagesDB.insertOrUpdate(it) }
        }
    }

    @After
    fun tearDown() {
        writeState(FirstRunState())
        resetSharedMessagesDatabase()
    }

    private fun writeState(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    private fun text(id: Long, at: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX) = Message(
        id = id, body = "Cheap loans", type = type, status = 0, participants = ArrayList(),
        date = (at / 1000).toInt(), read = true, threadId = SPAM, isMMS = false, attachment = null,
        senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    @Test
    fun itCountsTheSpamTextsOfTheWeekAndOpensTheSpamTab() {
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()

        val notification = notifications.single()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("2")
        val open = shadowOf(notification.contentIntent).savedIntent
        assertThat(open.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)).isEqualTo(MainTabLinks.TAB_BLOCKED)
    }

    @Test
    fun itWaitsUntilTheFirstSortIsPublished() {
        writeState(FirstRunState(phase = FirstRunPhase.SORT, sortPublished = false))

        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isFalse()
        assertThat(notifications).isEmpty()

        // The week was not used up by the wait.
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()
    }

    @Test
    fun theCountIsPureAndKeepsOnlyTextsThatArrivedInSpam() {
        val texts = listOf(
            WeeklyDigest.SpamText(SENDER, "Your verification code is 482913", now - DAY_MS, since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = now - DAY_MS, since = since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = since - 1, since = since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = now + DAY_MS, since = since),
        )

        assertThat(WeeklyDigest.computeTexts(texts, now)).isEqualTo(WeeklyDigest(held = 2, important = 1))
    }

    private companion object {
        const val SPAM = 71L
        const val SENDER = "+15550001234"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
