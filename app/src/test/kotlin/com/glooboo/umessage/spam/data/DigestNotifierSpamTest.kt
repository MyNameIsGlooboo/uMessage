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
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step H (section 5): the weekly summary counts the received texts of the chats in Spam that arrived
 * there in the last week, waits for the first sort, and opens the Spam tab, not the old list. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class DigestNotifierSpamTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 50 * CleanupPrefs.DIGEST_INTERVAL_MS
    private val since = now - 3 * DAY_MS

    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifications get() = shadowOf(manager).allNotifications

    @Before
    fun setUp() {
        DigestNotifier.resetDueGuard()
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
        DigestNotifier.resetDueGuard()
        DigestNotifier.readSpamTexts = { context, now -> DigestNotifier.spamTexts(context, now) }
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

    /** I08: one quiet notification; on the lock screen only its title shows, never a count or a text. */
    @Test
    fun theLockScreenShowsNoCountAndTheChannelIsQuiet() {
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()

        val notification = notifications.single()
        assertThat(notification.channelId).isEqualTo(DigestNotifier.CHANNEL_ID)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).contains("2")
        assertThat(text).doesNotContain("loans")
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)).isNull()
        assertThat(manager.getNotificationChannel(DigestNotifier.CHANNEL_ID).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    /** I08: a week in which no text arrived in Spam posts nothing. */
    @Test
    fun nothingIsPostedWhenNothingWasCounted() {
        // Only texts that do not count: one from before its chat went to Spam, one older than a week.
        DigestNotifier.readSpamTexts = { _, _ ->
            listOf(
                WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = since - DAY_MS, since = since),
                WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = now - 9 * DAY_MS, since = 1L),
            )
        }

        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isFalse()
        assertThat(notifications).isEmpty()
    }

    /** I08: with the summary switched off nothing is read or posted, and the week waits for the switch. */
    @Test
    fun nothingIsPostedWithTheSwitchOff() {
        var reads = 0
        DigestNotifier.readSpamTexts = { c, at -> reads++; DigestNotifier.spamTexts(c, at) }
        context.cleanupPrefs.digestOn = false

        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isFalse()
        assertThat(notifications).isEmpty()
        assertThat(reads).isEqualTo(0)

        context.cleanupPrefs.digestOn = true
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()
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
    fun checksRightAfterAPostedDigestPostNothingMore() {
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()

        // The week was just claimed: the next checks (the second within the pause) post nothing.
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now + 1_000L) }).isFalse()
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now + 2_000L) }).isFalse()
        assertThat(notifications).hasSize(1)
    }

    @Test
    fun aFailedCountKeepsTheWeek() {
        DigestNotifier.readSpamTexts = { _, _ -> error("cache gone") }
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isFalse()

        DigestNotifier.readSpamTexts = { c, at -> DigestNotifier.spamTexts(c, at) }
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()
    }

    @Test
    fun aCheckBeforeTheWeekIsUpReadsNothingAgainWithinTheHour() {
        var reads = 0
        DigestNotifier.readSpamTexts = { c, at -> reads++; DigestNotifier.spamTexts(c, at) }
        assertThat(offMainThread { DigestNotifier.postIfDue(context, now) }).isTrue()
        assertThat(reads).isEqualTo(1)

        // The week was just claimed: this check reads once and finds it not up; the next ones within the hour do not.
        offMainThread { DigestNotifier.postIfDue(context, now + 1_000L) }
        offMainThread { DigestNotifier.postIfDue(context, now + 30 * 60_000L) }
        assertThat(reads).isEqualTo(2)

        offMainThread { DigestNotifier.postIfDue(context, now + 2 * 60 * 60_000L) }
        assertThat(reads).isEqualTo(3)
    }

    @Test
    fun theTextThatPutItsChatInSpamCounts() {
        // H-15: spam_since is the arrival time in milliseconds, the stored text is dated in whole seconds; an MMS is
        // dated by its own date, a few minutes before its download placed the chat.
        val arrival = now - DAY_MS + 731L
        val texts = listOf(
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = arrival / 1000 * 1000, since = arrival),
            WeeklyDigest.SpamText(SENDER, "Picture ad", receivedAt = arrival - 5 * 60_000L, since = arrival),
            WeeklyDigest.SpamText(SENDER, "Old chat text", receivedAt = arrival - 60 * 60_000L, since = arrival),
        )

        assertThat(WeeklyDigest.computeTexts(texts, now).held).isEqualTo(2)
    }

    private fun heldWith(vararg extra: Message): Int {
        offMainThread { extra.forEach { context.messagesDB.insertOrUpdate(it) } }
        val texts = offMainThread { DigestNotifier.spamTexts(context, now) }
        return WeeklyDigest.computeTexts(texts, now).held
    }

    private fun mms(id: Long, at: Long) = text(id, at).copy(isMMS = true)

    @Test
    fun anMmsDownloadedLongAfterItsDateCounts() {
        // H-20: dated by its own date, downloaded hours later, and that download put the chat in Spam. It is stored
        // read, as every Spam MMS is.
        assertThat(heldWith(mms(6, since - 3 * HOUR_MS))).isEqualTo(3)
    }

    @Test
    fun anSmsFromBeforeTheChatWentToSpamDoesNotCount() {
        assertThat(heldWith(text(6, since - 3 * HOUR_MS))).isEqualTo(2)
    }

    @Test
    fun anMmsDatedMoreThanTheGraceBeforeDoesNotCount() {
        // 24 h plus the 10 min arrival grace; 25 h is outside.
        assertThat(heldWith(mms(6, since - 25 * HOUR_MS))).isEqualTo(2)
    }

    @Test
    fun onlyTheNewestMmsBeforeTheChatWentToSpamGetsTheGrace() {
        // J4-4: only the newest can have put the chat in Spam; an older one the user saw in Messages does not count.
        assertThat(heldWith(mms(6, since - 5 * HOUR_MS), mms(7, since - 3 * HOUR_MS))).isEqualTo(3)
    }

    @Test
    fun theCountIsPureAndKeepsOnlyTextsThatArrivedInSpam() {
        val texts = listOf(
            WeeklyDigest.SpamText(SENDER, "Your verification code is 482913", now - DAY_MS, since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = now - DAY_MS, since = since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = since - 60 * 60_000L, since = since),
            WeeklyDigest.SpamText(SENDER, "Cheap loans", receivedAt = now + DAY_MS, since = since),
        )

        assertThat(WeeklyDigest.computeTexts(texts, now)).isEqualTo(WeeklyDigest(held = 2, important = 1))
    }

    private companion object {
        const val SPAM = 71L
        const val SENDER = "+15550001234"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val HOUR_MS = 60L * 60 * 1000
    }
}
