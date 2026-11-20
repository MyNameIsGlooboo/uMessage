package com.glooboo.umessage.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ChannelHealth
import com.glooboo.umessage.helpers.ChannelIssue
import com.glooboo.umessage.helpers.ChannelProblem
import com.glooboo.umessage.helpers.NOTIFICATION_CHANNEL_ID
import com.glooboo.umessage.ui.icons.Symbols

/**
 * Why new messages would arrive silently: all of the app's notifications are off (APP), or a message channel is off,
 * silent or without sound (CHANNEL).
 */
enum class NotificationsBlock { APP, CHANNEL }

/**
 * RM-34: whether message notifications are blocked. [appEnabled] covers a denied POST_NOTIFICATIONS permission (Android
 * 13+) and "All notifications" turned off; [problem] is the worst problem of the message channels (N7), or null.
 */
fun notificationsBlock(appEnabled: Boolean, problem: ChannelProblem?): NotificationsBlock? = when {
    !appEnabled -> NotificationsBlock.APP
    problem != null -> NotificationsBlock.CHANNEL
    else -> null
}

fun Context.currentNotificationsBlock(): NotificationsBlock? = runCatching {
    val manager = NotificationManagerCompat.from(this)
    notificationsBlock(manager.areNotificationsEnabled(), ChannelHealth.current(this))
}.getOrNull()

/** N7: the problem to name when [block] is a channel that still shows but rings no sound; else null. */
internal fun quietChannelProblem(block: NotificationsBlock?, problem: ChannelProblem?): ChannelProblem? =
    problem?.takeIf { block == NotificationsBlock.CHANNEL && it.issue != ChannelIssue.BLOCKED }

/** The system page that turns [block] back on. */
fun Context.notificationsFixIntent(block: NotificationsBlock): Intent {
    val intent = if (block == NotificationsBlock.CHANNEL) {
        // N7: the page of the channel with the problem. The app only opens it; it never changes a channel.
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ChannelHealth.current(this)?.channelId ?: NOTIFICATION_CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    return intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * A banner on the main screen while message notifications are blocked, with a button to the setting that fixes it.
 * Re-checked every time the screen resumes, so it goes away as soon as the user comes back from settings.
 */
@Composable
fun NotificationsOffBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var block by remember { mutableStateOf(context.currentNotificationsBlock()) }
    var problem by remember { mutableStateOf(ChannelHealth.current(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                block = context.currentNotificationsBlock()
                problem = ChannelHealth.current(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var dismissed by remember { mutableStateOf(false) }
    val current = block ?: return
    val key = bannerKey(current, problem)
    // Hidden for a few weeks after "Not now"; a different kind of block, channel or issue (or the dismiss expiring)
    // shows it again.
    if (dismissed || isBannerDismissed(context, key)) return
    // A channel that still shows but rings no sound gets its own texts, naming the channel.
    val quiet = quietChannelProblem(current, problem)

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Icon(Symbols.NotificationsOff, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (quiet == null) {
                        stringResource(R.string.notifications_off_title)
                    } else {
                        stringResource(R.string.notifications_quiet_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (quiet == null) {
                        stringResource(R.string.notifications_off_body)
                    } else {
                        stringResource(R.string.notifications_quiet_body, quiet.channelName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // High contrast on the red container: the container's own on-colour as the fill, its fill as the text.
                Button(
                    onClick = { openNotificationSettings(context, current) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    val fixRes = if (quiet == null) R.string.notifications_off_fix else R.string.onb_perm_open_settings
                    Text(stringResource(fixRes))
                }
                TextButton(onClick = {
                    dismissBanner(context, key)
                    dismissed = true
                }) {
                    Text(stringResource(R.string.notifications_off_dismiss))
                }
            }
        }
    }
}

private const val BANNER_PREFS = "notifications_off_banner"
private const val BANNER_DISMISS_MS = 28L * 24 * 60 * 60 * 1000

/** "APP", or "CHANNEL:<channel id>:<issue>" (N7), so a "Not now" never hides a new problem on another channel. */
private fun bannerKey(block: NotificationsBlock, problem: ChannelProblem?): String =
    if (block == NotificationsBlock.CHANNEL && problem != null) {
        "${block.name}:${problem.channelId}:${problem.issue.name}"
    } else {
        block.name
    }

private fun isBannerDismissed(context: Context, key: String): Boolean {
    val prefs = context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE)
    if (prefs.getString("block", null) != key) return false
    return System.currentTimeMillis() - prefs.getLong("at", 0L) in 0 until BANNER_DISMISS_MS
}

private fun dismissBanner(context: Context, key: String) {
    context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE).edit()
        .putString("block", key).putLong("at", System.currentTimeMillis()).apply()
}

private fun openNotificationSettings(context: Context, block: NotificationsBlock) {
    try {
        context.startActivity(context.notificationsFixIntent(block))
    } catch (_: ActivityNotFoundException) {
        // Some ROMs have no per-app notification page: the app details page has a notifications entry too.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
