package com.glooboo.umessage.spam.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.CleanupPrefs
import com.glooboo.umessage.helpers.DailyChecks
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** I08: the weekly summary posts quietly, hides its counts on the lock screen, posts nothing when nothing was held. */
@RunWith(AndroidJUnit4::class)
class DigestNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 50 * CleanupPrefs.DIGEST_INTERVAL_MS
    private lateinit var db: SpamDatabase

    private val notifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    @Before
    fun setUp() {
        db = SpamTestWiring.freshSpamDatabase(context)
        context.cleanupPrefs.digestOn = true
    }

    @After
    fun tearDown() = SpamTestWiring.clearSpamDatabase()

    private fun hold(body: String, receivedAt: Long) {
        db.spamDao().insertBlocked(
            BlockedMessageEntity(
                address = "+15550100", body = body, subscriptionId = 1, isMms = false,
                receivedAt = receivedAt, reason = DecisionReason.UNKNOWN_SENDER, ruleId = null,
            )
        )
    }

    /** The week started long ago, so a digest is due at [now]. */
    private fun weekIsUp() {
        context.cleanupPrefs.claimDigest(now - CleanupPrefs.DIGEST_INTERVAL_MS)
    }

    @Test
    fun whenDueItPostsOneQuietNotificationWithoutTextOnTheLockScreen() {
        weekIsUp()
        hold("Win a free trip, tap now", now - 1000)
        hold("Cheap loans", now - 2000)

        assertThat(DigestNotifier.postIfDue(context, now)).isTrue()

        val notification = notifications.single()
        assertThat(notification.channelId).isEqualTo(DigestNotifier.CHANNEL_ID)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).contains("2")
        assertThat(text).doesNotContain("loans")
        assertThat(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)).isNull()
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getNotificationChannel(DigestNotifier.CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun nothingIsPostedWhenNothingWasHeld() {
        weekIsUp()
        hold("Old ad", now - 10 * CleanupPrefs.DIGEST_INTERVAL_MS)

        assertThat(DigestNotifier.postIfDue(context, now)).isFalse()
        assertThat(notifications).isEmpty()
    }

    @Test
    fun nothingIsPostedBeforeTheWeekIsUpOrWithTheSwitchOff() {
        hold("Cheap loans", now - 1000)

        assertThat(DigestNotifier.postIfDue(context, now)).isFalse()

        weekIsUp()
        context.cleanupPrefs.digestOn = false

        assertThat(DigestNotifier.postIfDue(context, now)).isFalse()
        assertThat(notifications).isEmpty()
    }

    @Test
    fun theDailyAlarmIsBookedOnceUnlessForced() {
        DailyChecks.arm(context)
        DailyChecks.arm(context)

        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
        assertThat(alarms.scheduledAlarms).hasSize(1)
        assertThat(alarms.scheduledAlarms.single().interval).isEqualTo(android.app.AlarmManager.INTERVAL_DAY)

        DailyChecks.arm(context, force = true)

        assertThat(alarms.scheduledAlarms).hasSize(1)
    }
}
