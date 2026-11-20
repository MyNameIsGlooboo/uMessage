package com.glooboo.umessage.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.CODE_CHANNEL_ID
import com.glooboo.umessage.helpers.ChannelIssue
import com.glooboo.umessage.helpers.ChannelProblem
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
        val aProblem = ChannelProblem(NOTIFICATION_CHANNEL_ID, "Received messages", ChannelIssue.BLOCKED)
        assertThat(notificationsBlock(appEnabled = false, problem = aProblem)).isEqualTo(NotificationsBlock.APP)
        assertThat(notificationsBlock(appEnabled = true, problem = null)).isNull()
        assertThat(notificationsBlock(appEnabled = true, problem = aProblem)).isEqualTo(NotificationsBlock.CHANNEL)
    }

    @Test
    fun silentCodeChannelIsDetectedAndTheFixOpensIt() {
        manager.createNotificationChannel(
            NotificationChannel(CODE_CHANNEL_ID, "Verification codes", NotificationManager.IMPORTANCE_LOW)
        )

        val block = context.currentNotificationsBlock()

        assertThat(block).isEqualTo(NotificationsBlock.CHANNEL)
        val intent = context.notificationsFixIntent(block!!)
        assertThat(intent.action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo(CODE_CHANNEL_ID)
    }

    @Test
    fun quietChannelProblemNamesOnlyAChannelThatStillShows() {
        val blocked = ChannelProblem(NOTIFICATION_CHANNEL_ID, "Received messages", ChannelIssue.BLOCKED)
        val quiet = ChannelProblem(CODE_CHANNEL_ID, "Verification codes", ChannelIssue.QUIET)
        val noSound = ChannelProblem(NOTIFICATION_CHANNEL_ID, "Received messages", ChannelIssue.NO_SOUND)

        assertThat(quietChannelProblem(NotificationsBlock.CHANNEL, blocked)).isNull()
        assertThat(quietChannelProblem(NotificationsBlock.CHANNEL, quiet)).isEqualTo(quiet)
        assertThat(quietChannelProblem(NotificationsBlock.CHANNEL, noSound)).isEqualTo(noSound)
        assertThat(quietChannelProblem(NotificationsBlock.APP, quiet)).isNull()
        assertThat(quietChannelProblem(NotificationsBlock.CHANNEL, null)).isNull()
    }

    @Test
    fun bannerKeyNamesTheBlockTheChannelAndTheIssue() {
        val quiet = ChannelProblem(CODE_CHANNEL_ID, "Verification codes", ChannelIssue.QUIET)

        assertThat(BannerIssue(NotificationsBlock.APP, null).key).isEqualTo("APP")
        assertThat(BannerIssue(NotificationsBlock.CHANNEL, quiet).key).isEqualTo("CHANNEL:$CODE_CHANNEL_ID:QUIET")
    }

    @Test
    fun notNowOnOneProblemStillShowsAnother() {
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Received messages", NotificationManager.IMPORTANCE_NONE)
        )
        manager.createNotificationChannel(
            NotificationChannel(CODE_CHANNEL_ID, "Verification codes", NotificationManager.IMPORTANCE_LOW)
        )
        val issues = context.bannerIssues()
        fun shown() = shownBannerIssue(issues) { isBannerDismissed(context, it) }
        assertThat(shown()?.problem?.channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)

        dismissBanner(context, shown()!!.key)

        // The worse problem is dismissed; the smaller one on the other channel is still named.
        val next = shown()
        assertThat(next?.problem?.channelId).isEqualTo(CODE_CHANNEL_ID)
        assertThat(next?.problem?.issue).isEqualTo(ChannelIssue.QUIET)
        val intent = context.notificationsFixIntent(next!!.block, next.problem?.channelId)
        assertThat(intent.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo(CODE_CHANNEL_ID)

        dismissBanner(context, next.key)
        assertThat(shown()).isNull()
    }

    @Test
    fun aDismissalRunsOutAfterFourWeeks() {
        val key = BannerIssue(NotificationsBlock.APP, null).key
        dismissBanner(context, key, now = 0L)

        assertThat(isBannerDismissed(context, key, now = 1_000L)).isTrue()
        assertThat(isBannerDismissed(context, key, now = FIVE_WEEKS_MS)).isFalse()
        assertThat(isBannerDismissed(context, "CHANNEL:$CODE_CHANNEL_ID:QUIET", now = 1_000L)).isFalse()
    }

    @Test
    fun appSwitchOffIsTheOnlyBannerIssue() {
        shadowOf(manager).setNotificationsEnabled(false)
        manager.createNotificationChannel(
            NotificationChannel(CODE_CHANNEL_ID, "Verification codes", NotificationManager.IMPORTANCE_LOW)
        )

        assertThat(context.bannerIssues()).containsExactly(BannerIssue(NotificationsBlock.APP, null))
    }

    @Test
    fun channelWithoutSoundIsDetected() {
        val channel =
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Received messages", NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)

        assertThat(context.currentNotificationsBlock()).isEqualTo(NotificationsBlock.CHANNEL)
    }

    private companion object {
        const val FIVE_WEEKS_MS = 35L * 24 * 60 * 60 * 1000
    }
}
