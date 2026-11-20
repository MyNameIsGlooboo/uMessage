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

/** The system page that turns [block] back on; for CHANNEL the page of [channelId] (null: the worst channel now). */
fun Context.notificationsFixIntent(block: NotificationsBlock, channelId: String? = null): Intent {
    val intent = if (block == NotificationsBlock.CHANNEL) {
        // N7: the page of the channel with the problem. The app only opens it; it never changes a channel.
        val channel = channelId ?: ChannelHealth.current(this)?.channelId ?: NOTIFICATION_CHANNEL_ID
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    return intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** What the banner can name: a block and, for CHANNEL, the channel with the problem. */
internal data class BannerIssue(val block: NotificationsBlock, val problem: ChannelProblem?) {
    /** "APP", or "CHANNEL:<channel id>:<issue>" (N7), so a "Not now" never hides a new problem on another channel. */
    val key: String
        get() = if (problem != null) "${block.name}:${problem.channelId}:${problem.issue.name}" else block.name
}

/**
 * R7: one read of the app switch and the message channels. APP alone while the app switch is off, else every channel
 * problem, worst first. Empty when all is fine or the read failed.
 */
internal fun Context.bannerIssues(): List<BannerIssue> = runCatching {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
        listOf(BannerIssue(NotificationsBlock.APP, null))
    } else {
        ChannelHealth.currentProblems(this).map { BannerIssue(NotificationsBlock.CHANNEL, it) }
    }
}.getOrDefault(emptyList())

/** R6: the worst issue that was not dismissed, so a "Not now" on one problem never hides another. */
internal fun shownBannerIssue(issues: List<BannerIssue>, isDismissed: (String) -> Boolean): BannerIssue? =
    issues.firstOrNull { !isDismissed(it.key) }

/**
 * A banner on the main screen while message notifications are blocked, with a button to the setting that fixes it.
 * Re-checked every time the screen resumes, so it goes away as soon as the user comes back from settings.
 */
@Composable
fun NotificationsOffBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var issues by remember { mutableStateOf(context.bannerIssues()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) issues = context.bannerIssues()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // "Not now" in this screen, per issue key.
    var dismissedKeys by remember { mutableStateOf(emptySet<String>()) }
    // Hidden for a few weeks after "Not now"; a different kind of block, channel or issue (or the dismiss expiring)
    // shows it again.
    val shown = shownBannerIssue(issues) { it in dismissedKeys || isBannerDismissed(context, it) } ?: return
    val current = shown.block
    // A channel that still shows but rings no sound gets its own texts, naming the channel.
    val quiet = quietChannelProblem(current, shown.problem)

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
                    onClick = { openNotificationSettings(context, shown) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    val fixRes = if (quiet == null) R.string.notifications_off_fix else R.string.onb_perm_open_settings
                    Text(stringResource(fixRes))
                }
                TextButton(onClick = {
                    dismissBanner(context, shown.key)
                    dismissedKeys = dismissedKeys + shown.key
                }) {
                    Text(stringResource(R.string.notifications_off_dismiss))
                }
            }
        }
    }
}

private const val BANNER_PREFS = "notifications_off_banner"
private const val BANNER_DISMISS_MS = 28L * 24 * 60 * 60 * 1000

/** R6: one dismissal per issue key. */
private const val DISMISSED_AT = "at:"

internal fun isBannerDismissed(context: Context, key: String, now: Long = System.currentTimeMillis()): Boolean {
    val prefs = context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE)
    val at = when {
        prefs.contains("$DISMISSED_AT$key") -> prefs.getLong("$DISMISSED_AT$key", 0L)
        // Before R6 the one dismissal was stored as "block" and "at".
        prefs.getString("block", null) == key -> prefs.getLong("at", 0L)
        else -> return false
    }
    return now - at in 0 until BANNER_DISMISS_MS
}

internal fun dismissBanner(context: Context, key: String, now: Long = System.currentTimeMillis()) {
    context.getSharedPreferences(BANNER_PREFS, Context.MODE_PRIVATE).edit().putLong("$DISMISSED_AT$key", now).apply()
}

private fun openNotificationSettings(context: Context, issue: BannerIssue) {
    try {
        context.startActivity(context.notificationsFixIntent(issue.block, issue.problem?.channelId))
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
