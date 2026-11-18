package com.glooboo.umessage.ui.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.main.NotificationsBlock
import com.glooboo.umessage.ui.onboarding.PermissionItem
import com.glooboo.umessage.ui.onboarding.findActivity
import com.glooboo.umessage.ui.onboarding.isGranted
import com.glooboo.umessage.ui.onboarding.openAppSettings

/** One card of the permissions list: what it is, its live status and the button that opens the right place. */
internal class SetupRowUi(
    val title: String,
    val reason: String,
    val mark: SetupMark,
    val optional: Boolean,
    @StringRes val actionRes: Int,
    val onAction: () -> Unit,
    val note: String? = null,
)

/** What the rows are built from, read live on this composition. */
internal class SetupLive(
    val items: List<PermissionItem>,
    val isDefault: Boolean,
    val roleDenied: Boolean,
    val block: NotificationsBlock?,
    val denied: Set<String>,
)

internal class SetupActions(
    val requestPermissions: (List<String>) -> Unit,
    val requestDefaultSms: () -> Unit,
    val openNotifications: (NotificationsBlock?) -> Unit,
)

/**
 * Asks for permissions and remembers (across a rotation too) the ones Android will not ask for again. A permission is
 * only treated so when its rationale was due before the request and is gone after it: Back on the first ask is not
 * "don't ask again".
 */
internal class PermissionAsker(
    private val context: Context,
    private val permanentlyDenied: MutableState<Set<String>>,
    private val rationaleBefore: MutableState<Set<String>>,
) {
    var launch: (Array<String>) -> Unit = {}

    val denied: Set<String> get() = permanentlyDenied.value

    fun request(permissions: List<String>) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        if (missing.all { it in denied }) {
            context.openAppSettings()
            return
        }
        rationaleBefore.value = missing.filter { shouldShowRationale(it) }.toSet()
        launch(missing.toTypedArray())
    }

    fun onResult(result: Map<String, Boolean>) {
        val blocked = result.filter { (perm, ok) -> !ok && perm in rationaleBefore.value && !shouldShowRationale(perm) }
        permanentlyDenied.value = permanentlyDenied.value + blocked.keys
    }

    private fun shouldShowRationale(permission: String): Boolean {
        val activity = context.findActivity() ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}

internal fun buildSetupRows(context: Context, live: SetupLive, actions: SetupActions): List<SetupRowUi> =
    listOf(defaultSmsRow(context, live, actions)) +
        live.items.filterNot { it.isNotifications() }.map { permissionRow(context, it, live, actions) } +
        notificationsRow(context, live, actions)

private fun defaultSmsRow(context: Context, live: SetupLive, actions: SetupActions) = SetupRowUi(
    title = context.getString(R.string.onb_done_summary_default),
    reason = context.getString(R.string.onb_default_body),
    mark = checkableMark(live.isDefault),
    optional = false,
    actionRes = if (live.roleDenied) R.string.onb_perm_open_settings else R.string.w3c_checklist_action_set,
    onAction = actions.requestDefaultSms,
    note = if (live.roleDenied && !live.isDefault) context.getString(R.string.onb_default_denied) else null,
)

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

/** Notifications on every Android version: the permission (13+), the app switch and the messages channel. */
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
    )
}

private fun PermissionItem.isNotifications() = Manifest.permission.POST_NOTIFICATIONS in permissions
