package com.glooboo.umessage.helpers

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import org.fossify.commons.extensions.notificationManager

/** What is wrong with a message channel, worst first. */
enum class ChannelIssue { BLOCKED, QUIET, NO_SOUND }

/** What Android reports for one channel. */
data class ChannelState(val id: String, val name: String, val importance: Int, val hasSound: Boolean)

data class ChannelProblem(val channelId: String, val channelName: String, val issue: ChannelIssue)

/** N7: are the channels that carry received messages loud enough? Read only: the app never changes a channel. */
object ChannelHealth {

    /** BLOCKED: importance NONE. QUIET: importance below DEFAULT. NO_SOUND: DEFAULT or higher without a sound. */
    fun issueOf(state: ChannelState): ChannelIssue? = when {
        state.importance == NotificationManager.IMPORTANCE_NONE -> ChannelIssue.BLOCKED
        state.importance < NotificationManager.IMPORTANCE_DEFAULT -> ChannelIssue.QUIET
        !state.hasSound -> ChannelIssue.NO_SOUND
        else -> null
    }

    /** Ids of the channels received messages can use: codes, normal, and the SIM channels of [dualSimSlots]. */
    fun idsInUse(dualSimSlots: Set<Int>): List<String> =
        listOf(CODE_CHANNEL_ID, NOTIFICATION_CHANNEL_ID) + dualSimSlots.sorted().map { "$SIM_CHANNEL_PREFIX$it" }

    /** Every problem among [states] (given in the order of [idsInUse]), worst first; a tie keeps that order. */
    fun problems(states: List<ChannelState>): List<ChannelProblem> = states
        .mapNotNull { state -> issueOf(state)?.let { ChannelProblem(state.id, state.name, it) } }
        .sortedBy { it.issue.ordinal }

    /**
     * The worst problem among [states] (given in the order of [idsInUse]); a tie goes to the earlier one.
     * Null = healthy.
     */
    fun worst(states: List<ChannelState>): ChannelProblem? = problems(states).firstOrNull()

    /** Reads the channels in use that exist. Null when all are fine or the read failed. */
    fun current(context: Context): ChannelProblem? = currentProblems(context).firstOrNull()

    /** Reads the channels in use that exist: every problem, worst first. Empty when all are fine or the read failed. */
    fun currentProblems(context: Context): List<ChannelProblem> = runCatching {
        val manager = context.notificationManager
        // A channel that does not exist yet is skipped: Android creates it loud on first use.
        val states = idsInUse(dualSimSlots(context)).mapNotNull { id ->
            manager.getNotificationChannel(id)?.let {
                ChannelState(id, it.name.toString(), it.importance, it.sound != null)
            }
        }
        problems(states)
    }.getOrDefault(emptyList())

    /** The SIM slots in use when the phone is dual-SIM, as NotificationHelper.getSimChannel tells; else empty. */
    private fun dualSimSlots(context: Context): Set<Int> {
        val canReadSims = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!canReadSims) return emptySet()
        return runCatching {
            val subs = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            if (subs.size < 2) emptySet() else subs.map { it.simSlotIndex }.toSet()
        }.getOrDefault(emptySet())
    }
}
