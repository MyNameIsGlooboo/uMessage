package com.glooboo.umessage.helpers

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

/** N7: which message channel is off, silent or without sound, and which channels count at all. */
@RunWith(AndroidJUnit4::class)
class ChannelHealthTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun state(id: String, importance: Int, hasSound: Boolean = true) =
        ChannelState(id, "Channel $id", importance, hasSound)

    @Test
    fun importanceNoneIsBlocked() {
        assertThat(ChannelHealth.issueOf(state("a", NotificationManager.IMPORTANCE_NONE)))
            .isEqualTo(ChannelIssue.BLOCKED)
    }

    @Test
    fun importanceLowOrMinIsQuiet() {
        for (importance in listOf(NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN)) {
            for (hasSound in listOf(true, false)) {
                assertThat(ChannelHealth.issueOf(state("a", importance, hasSound))).isEqualTo(ChannelIssue.QUIET)
            }
        }
    }

    @Test
    fun defaultOrHighWithSoundIsHealthy() {
        assertThat(ChannelHealth.issueOf(state("a", NotificationManager.IMPORTANCE_DEFAULT))).isNull()
        assertThat(ChannelHealth.issueOf(state("a", NotificationManager.IMPORTANCE_HIGH))).isNull()
    }

    @Test
    fun highWithoutSoundIsNoSound() {
        assertThat(ChannelHealth.issueOf(state("a", NotificationManager.IMPORTANCE_HIGH, hasSound = false)))
            .isEqualTo(ChannelIssue.NO_SOUND)
    }

    @Test
    fun idsInUseFollowTheSims() {
        assertThat(ChannelHealth.idsInUse(emptySet()))
            .containsExactly(CODE_CHANNEL_ID, NOTIFICATION_CHANNEL_ID).inOrder()
        assertThat(ChannelHealth.idsInUse(setOf(1, 0))).containsExactly(
            CODE_CHANNEL_ID, NOTIFICATION_CHANNEL_ID, "fossify_messages_slot_0", "fossify_messages_slot_1",
        ).inOrder()
    }

    @Test
    fun worstProblemWinsAndTiesGoToCodes() {
        val slotBlocked = ChannelHealth.worst(
            listOf(
                state(CODE_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH),
                state(NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH, hasSound = false),
                state("fossify_messages_slot_0", NotificationManager.IMPORTANCE_NONE),
            )
        )
        assertThat(slotBlocked?.channelId).isEqualTo("fossify_messages_slot_0")
        assertThat(slotBlocked?.issue).isEqualTo(ChannelIssue.BLOCKED)

        val tie = ChannelHealth.worst(
            listOf(
                state(CODE_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW),
                state(NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW),
            )
        )
        assertThat(tie?.channelId).isEqualTo(CODE_CHANNEL_ID)
        assertThat(tie?.issue).isEqualTo(ChannelIssue.QUIET)

        val healthy = ChannelHealth.worst(
            listOf(
                state(CODE_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH),
                state(NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        assertThat(healthy).isNull()
    }

    @Test
    fun noChannelsYetIsHealthy() {
        assertThat(ChannelHealth.current(context)).isNull()
    }

    @Test
    fun loweredCodeChannelIsFound() {
        manager.createNotificationChannel(
            NotificationChannel(CODE_CHANNEL_ID, "Verification codes", NotificationManager.IMPORTANCE_LOW)
        )

        val problem = ChannelHealth.current(context)

        assertThat(problem?.channelId).isEqualTo(CODE_CHANNEL_ID)
        assertThat(problem?.channelName).isEqualTo("Verification codes")
        assertThat(problem?.issue).isEqualTo(ChannelIssue.QUIET)
    }

    @Test
    fun channelWithoutSoundIsFound() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Received SMS", NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)

        val problem = ChannelHealth.current(context)

        assertThat(problem?.channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
        assertThat(problem?.issue).isEqualTo(ChannelIssue.NO_SOUND)
    }

    @Test
    fun slotChannelCountsOnlyOnADualSimPhone() {
        val app = context as Application
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        manager.createNotificationChannel(
            NotificationChannel("fossify_messages_slot_0", "SIM 1", NotificationManager.IMPORTANCE_NONE)
        )
        val subscriptions = shadowOf(context.getSystemService(SubscriptionManager::class.java))
        val sim1 = SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0).buildSubscriptionInfo()
        val sim2 = SubscriptionInfoBuilder.newBuilder().setId(2).setSimSlotIndex(1).buildSubscriptionInfo()

        subscriptions.setActiveSubscriptionInfos(sim1)
        assertThat(ChannelHealth.current(context)).isNull()

        subscriptions.setActiveSubscriptionInfos(sim1, sim2)
        val problem = ChannelHealth.current(context)
        assertThat(problem?.channelId).isEqualTo("fossify_messages_slot_0")
        assertThat(problem?.issue).isEqualTo(ChannelIssue.BLOCKED)
    }
}
