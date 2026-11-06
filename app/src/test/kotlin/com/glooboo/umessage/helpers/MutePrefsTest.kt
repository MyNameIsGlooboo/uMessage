package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** N05: a conversation muted from its menu. */
@RunWith(AndroidJUnit4::class)
class MutePrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clock = 1_000_000L
    private val mutes = MutePrefs(context.getSharedPreferences("mute_test", Context.MODE_PRIVATE)) { clock }

    @Test
    fun aMuteEndsWhenItsTimeIsUp() {
        mutes.mute(7, MuteDuration.OneHour)

        assertThat(mutes.isMuted(7)).isTrue()
        assertThat(mutes.isMuted(8)).isFalse()
        assertThat(mutes.mutedUntil(7)).isEqualTo(1_000_000L + 3_600_000L)

        clock += 3_600_001L
        assertThat(mutes.isMuted(7)).isFalse()
        assertThat(mutes.mutedUntil(7)).isEqualTo(0L)
    }

    @Test
    fun theChoicesLastAnHourEightHoursAWeekOrForever() {
        mutes.mute(1, MuteDuration.EightHours)
        mutes.mute(2, MuteDuration.OneWeek)
        mutes.mute(3, MuteDuration.Always)

        assertThat(mutes.mutedUntil(1)).isEqualTo(clock + 8 * 3_600_000L)
        assertThat(mutes.mutedUntil(2)).isEqualTo(clock + 7 * 24 * 3_600_000L)
        clock += 365L * 24 * 3_600_000L
        assertThat(mutes.isMuted(3)).isTrue()
        assertThat(mutes.mutedUntil(3)).isEqualTo(MutePrefs.ALWAYS)
    }

    @Test
    fun unmuteClearsAMute() {
        mutes.mute(7, MuteDuration.Always)
        mutes.unmute(7)

        assertThat(mutes.isMuted(7)).isFalse()
    }

    @Test
    fun aMutedChatPostsQuietlyAndAMutedCodeStillPosts() {
        MutePrefs.get(context).mute(77L, MuteDuration.Always)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        offMainThread {
            NotificationHelper(context).showMessageNotification(
                messageId = 1L, isMms = false, address = "+15550100", body = "Your code is 482913", threadId = 77L,
                bitmap = null, sender = "Ali",
            )
        }

        val n = shadowOf(manager).allNotifications.single()
        assertThat(n.channelId).isEqualTo(MutedChannel.ID)
        assertThat(manager.getNotificationChannel(MutedChannel.ID).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_LOW)
        val copy = context.getString(R.string.onb_notif_copy_code)
        assertThat(n.actions.map { it.title.toString() }).contains(copy)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("482913")
    }
}
