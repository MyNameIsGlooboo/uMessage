package com.glooboo.umessage.ui.setup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.glooboo.umessage.helpers.ChannelHealth
import com.glooboo.umessage.helpers.ChannelProblem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.ui.main.NotificationsBlock
import com.glooboo.umessage.ui.main.notificationsBlock
import com.glooboo.umessage.ui.onboarding.OnboardingActivity
import com.glooboo.umessage.ui.onboarding.permissionItems

/**
 * B2: true when the Setup page has something the user should fix (the same rows the page builds, plus its battery
 * step). The Setup row on the main list uses it for its summary and its dot; the page shows the same as its headline.
 */
fun setupAttentionNow(context: Context): Boolean = runCatching {
    val live = setupLive(context, roleDenied = false, denied = emptySet())
    val noActions = SetupActions(
        requestPermissions = {},
        requestDefaultSms = {},
        openNotifications = { _ -> },
    )
    val rows = buildSetupRows(context, live, noActions).map { it.headlineState() }
    setupNeedsAttention(rows, backgroundLimited(context))
}.getOrDefault(false)

/**
 * Android itself limits uMessage in the background (Restricted, or the restricted standby bucket). Merely not being
 * on "Unrestricted" is the default for most phones and is not a problem to flag on the main list.
 */
private fun backgroundLimited(context: Context): Boolean =
    OemHelper.backgroundStatus(context).let { it.backgroundRestricted || it.restrictedBucket }

/** The live facts the Setup page reads. Both the page and the Setup row on the main list build from this one read. */
internal fun setupLive(context: Context, roleDenied: Boolean, denied: Set<String>): SetupLive {
    val problem = ChannelHealth.current(context)
    return SetupLive(
        items = permissionItems(),
        isDefault = OnboardingActivity.isDefaultSmsApp(context),
        roleDenied = roleDenied,
        block = notificationsBlockOf(context, problem),
        denied = denied,
        channelProblem = problem,
    )
}

/** As currentNotificationsBlock, from a channel [problem] this composition has read already. */
private fun notificationsBlockOf(context: Context, problem: ChannelProblem?): NotificationsBlock? = runCatching {
    notificationsBlock(NotificationManagerCompat.from(context).areNotificationsEnabled(), problem)
}.getOrNull()
