package com.glooboo.umessage.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success

/** One runtime permission group of the Allow step. */
internal data class PermissionItem(
    val titleRes: Int,
    val reasonRes: Int,
    val icon: ImageVector,
    val permissions: List<String>,
    /** I12: an optional item is marked "Optional" and does not count towards "All set". */
    val optional: Boolean = false,
)

/** True when every item that is not optional is granted. */
internal fun requiredGranted(items: List<PermissionItem>, granted: Map<PermissionItem, Boolean>): Boolean =
    items.all { it.optional || granted[it] == true }

/** J23: the SMS item. Google Play: SMS permissions are asked only while uMessage is the default SMS app. */
internal fun PermissionItem.isSms() = Manifest.permission.READ_SMS in permissions

/**
 * The permissions "Allow all" may ask for: every missing item, but not the SMS item while uMessage is not the
 * default SMS app (the role comes first; the system grants the SMS permissions with it).
 */
internal fun askablePermissions(
    items: List<PermissionItem>,
    isDefault: Boolean,
    isGranted: (PermissionItem) -> Boolean,
): List<String> =
    items.filter { !isGranted(it) && (isDefault || !it.isSms()) }.flatMap { it.permissions }

internal fun permissionItems(): List<PermissionItem> = buildList {
    add(
        PermissionItem(
            R.string.onb_perm_contacts, R.string.onb_perm_contacts_reason, Symbols.Contacts,
            listOf(Manifest.permission.READ_CONTACTS),
            optional = true,
        ),
    )
    add(
        PermissionItem(
            R.string.onb_perm_sms, R.string.onb_perm_sms_reason, Symbols.Sms,
            listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
            ),
        ),
    )
    add(
        PermissionItem(
            R.string.onb_perm_phone, R.string.onb_perm_phone_reason, Symbols.Smartphone,
            listOf(Manifest.permission.READ_PHONE_STATE),
            optional = true,
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionItem(
                R.string.onb_perm_notifications, R.string.onb_perm_notifications_reason,
                Symbols.Notifications,
                listOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
}

/** The default-SMS-app part of the Allow page: its status and the actions for it. */
internal class DefaultSmsState(
    val isDefault: Boolean,
    val denied: Boolean,
    val unavailable: Boolean,
    val onRequest: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** Test tag of the "Allow all" button. */
internal const val ALLOW_ALL_TAG = "onb_allow_all"

/** Test tag of the button to Android's default apps page. */
internal const val DEFAULT_SETTINGS_TAG = "onb_default_settings"

/**
 * The Allow step: one "Allow all" button on top and a compact checklist below it. A row that is not done can be
 * tapped to ask for just that item, or to open Android settings when the system will not ask again.
 */
@Composable
internal fun AllowPage(
    items: List<PermissionItem>,
    granted: Map<PermissionItem, Boolean>,
    permanentlyDenied: Set<String>,
    defaultSms: DefaultSmsState,
    onRequest: (PermissionItem) -> Unit,
    onAllowAll: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allDone = defaultSms.isDefault && requiredGranted(items, granted)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageTitle(stringResource(R.string.onb_perm_title))
        PageBody(stringResource(R.string.onb_allow_intro))
        if (allDone) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Symbols.CheckCircleFilled, null, tint = MaterialTheme.colorScheme.success)
                Text(stringResource(R.string.onb_allow_all_set), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = onAllowAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(ALLOW_ALL_TAG),
            ) { Text(stringResource(R.string.onb_perm_allow_all)) }
        }
        ChecklistRow(
            title = stringResource(R.string.onb_done_summary_default),
            reason = stringResource(R.string.onb_default_body),
            ok = defaultSms.isDefault,
            blocked = false,
            onClick = defaultSms.onRequest,
        )
        if (defaultSms.denied && !defaultSms.isDefault) {
            Text(
                stringResource(R.string.onb_default_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (defaultSms.unavailable && !defaultSms.isDefault) {
            Text(
                stringResource(R.string.onb_default_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // M-UI7: after a refusal Android may not show its dialog again, so Android's default apps page is offered
        // too, as on the Setup page. Without it a second refusal left no way forward on this page.
        if ((defaultSms.denied || defaultSms.unavailable) && !defaultSms.isDefault) {
            OutlinedButton(
                onClick = defaultSms.onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp).testTag(DEFAULT_SETTINGS_TAG),
            ) {
                Text(stringResource(R.string.onb_default_open_settings))
            }
        }
        items.forEach { item ->
            val ok = granted[item] == true
            val roleFirst = item.isSms() && !defaultSms.isDefault
            val blocked = !ok && !roleFirst && item.permissions.any { it in permanentlyDenied }
            ChecklistRow(
                title = stringResource(item.titleRes),
                reason = stringResource(item.reasonRes),
                ok = ok,
                blocked = blocked,
                optional = item.optional,
                // J23: no SMS permission is asked before the default SMS app role.
                onClick = rowClick(roleFirst, blocked, defaultSms.onRequest, onOpenSettings) { onRequest(item) },
            )
        }
    }
}

private fun rowClick(
    roleFirst: Boolean,
    blocked: Boolean,
    onRole: () -> Unit,
    onSettings: () -> Unit,
    onAsk: () -> Unit,
): () -> Unit = when {
    roleFirst -> onRole
    blocked -> onSettings
    else -> onAsk
}

/**
 * One line of the checklist: a status mark, the name and a short status. The one-purpose explanation is not shown;
 * it is the row's content description for screen readers.
 */
@Composable
private fun ChecklistRow(
    title: String,
    reason: String,
    ok: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
    optional: Boolean = false,
) {
    val status = stringResource(
        when {
            ok -> R.string.onb_allow_done
            blocked -> R.string.onb_perm_open_settings
            optional -> R.string.onb_allow_optional
            else -> R.string.onb_allow_needed
        },
    )
    val statusColor = when {
        ok -> MaterialTheme.colorScheme.success
        blocked -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (ok) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $reason"
                stateDescription = status
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ok) {
            Icon(Symbols.CheckCircleFilled, null, tint = MaterialTheme.colorScheme.success)
        } else {
            Box(
                Modifier
                    .size(24.dp)
                    .padding(2.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.labelLarge, color = statusColor)
    }
}
