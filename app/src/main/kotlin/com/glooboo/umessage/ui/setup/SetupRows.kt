package com.glooboo.umessage.ui.setup

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ChannelProblem
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.ui.main.NotificationsBlock
import com.glooboo.umessage.ui.main.quietChannelProblem
import com.glooboo.umessage.ui.onboarding.PermissionItem
import com.glooboo.umessage.ui.onboarding.isGranted

/** One card of the permissions list: what it is, its live status and the button that opens the right place. */
internal class SetupRowUi(
    val title: String,
    val reason: String,
    val mark: SetupMark,
    val optional: Boolean,
    @StringRes val actionRes: Int,
    val onAction: () -> Unit,
    val note: String? = null,
    /** The step cannot be done on this phone (no SMS role to give): it never holds the headline back. */
    val unavailable: Boolean = false,
)

/** The row as the headline sees it: an unavailable step counts as not required, as in the first-day checklist. */
internal fun SetupRowUi.headlineState() = SetupRowState(mark, required = !optional && !unavailable)

/** What the rows are built from, read live on this composition. */
internal class SetupLive(
    val items: List<PermissionItem>,
    val isDefault: Boolean,
    val roleDenied: Boolean,
    val block: NotificationsBlock?,
    val denied: Set<String>,
    /** R7: the worst message-channel problem, from the same read as [block]. */
    val channelProblem: ChannelProblem? = null,
)

internal class SetupActions(
    val requestPermissions: (List<String>) -> Unit,
    val requestDefaultSms: () -> Unit,
    val openNotifications: (NotificationsBlock?) -> Unit,
)

internal fun buildSetupRows(context: Context, live: SetupLive, actions: SetupActions): List<SetupRowUi> =
    listOf(defaultSmsRow(context, live, actions)) +
        live.items.filterNot { it.isNotifications() }.map { permissionRow(context, it, live, actions) } +
        notificationsRow(context, live, actions)

private fun defaultSmsRow(context: Context, live: SetupLive, actions: SetupActions): SetupRowUi {
    val unavailable = DefaultSmsRole.status(context) is DefaultSmsRole.Status.Unavailable
    return SetupRowUi(
        title = context.getString(R.string.onb_done_summary_default),
        reason = context.getString(R.string.onb_default_body),
        mark = checkableMark(live.isDefault),
        optional = false,
        actionRes = if (live.roleDenied) R.string.onb_perm_open_settings else R.string.w3c_checklist_action_set,
        onAction = actions.requestDefaultSms,
        note = defaultSmsNote(context, live, unavailable),
        unavailable = unavailable,
    )
}

/** Role not available: say where to set the app by hand. Role refused: say that. Otherwise no note. */
private fun defaultSmsNote(context: Context, live: SetupLive, unavailable: Boolean): String? = when {
    unavailable -> context.getString(R.string.onb_default_unavailable)
    live.roleDenied && !live.isDefault -> context.getString(R.string.onb_default_denied)
    else -> null
}

private fun permissionRow(context: Context, item: PermissionItem, live: SetupLive, actions: SetupActions): SetupRowUi {
    val granted = context.isGranted(item.permissions)
    val blocked = !granted && item.permissions.any { it in live.denied }
    return SetupRowUi(
        title = context.getString(item.titleRes),
        reason = context.getString(item.reasonRes),
        mark = checkableMark(granted),
        optional = item.optional,
        actionRes = if (blocked) R.string.onb_perm_open_settings else R.string.setup_action_allow,
        onAction = { actions.requestPermissions(item.permissions) },
    )
}

/** Notifications on every Android version: the permission (13+), the app switch and the message channels. */
private fun notificationsRow(context: Context, live: SetupLive, actions: SetupActions): SetupRowUi {
    val askable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !context.isGranted(listOf(Manifest.permission.POST_NOTIFICATIONS)) &&
        Manifest.permission.POST_NOTIFICATIONS !in live.denied
    return SetupRowUi(
        title = context.getString(R.string.onb_perm_notifications),
        reason = context.getString(R.string.onb_perm_notifications_reason),
        mark = checkableMark(live.block == null),
        optional = false,
        actionRes = if (askable) R.string.setup_action_allow else R.string.onb_perm_open_settings,
        onAction = {
            if (askable) {
                actions.requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                actions.openNotifications(live.block)
            }
        },
        note = notificationsNote(context, live),
    )
}

/** N7: a message channel that still shows but rings no sound is named. Otherwise no note. */
private fun notificationsNote(context: Context, live: SetupLive): String? {
    if (live.block != NotificationsBlock.CHANNEL) return null
    val quiet = quietChannelProblem(live.block, live.channelProblem) ?: return null
    return context.getString(R.string.notifications_quiet_body, quiet.channelName)
}

private fun PermissionItem.isNotifications() = Manifest.permission.POST_NOTIFICATIONS in permissions
