package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.ui.components.GroupShape
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.icons.Symbols

/** Test tag of a group row: "allow_group_CODE" or "allow_group_WROTE". */
internal fun groupRowTag(group: AllowGroup) = "allow_group_${group.name}"

/** Test tag of a group row's switch. */
internal fun groupSwitchTag(group: AllowGroup) = "allow_group_switch_${group.name}"

private fun groupIcon(group: AllowGroup): ImageVector = when (group) {
    AllowGroup.CODE -> Symbols.Tag
    AllowGroup.WROTE -> Symbols.Send
}

private fun groupBody(group: AllowGroup): Int = when (group) {
    AllowGroup.CODE -> R.string.pt_group_code_body
    AllowGroup.WROTE -> R.string.pt_group_wrote_body
}

/**
 * D3 A, 4.5: the two group rules at the top of the "Always allow" list, one row each: the name, the number of senders
 * on its second line, and its own switch. A tap on the row opens the member list; the switch asks first before it goes
 * off (the screen shows the confirm).
 */
@Composable
internal fun AllowGroupRowItem(
    row: AllowGroupRow,
    shapeIndex: Int,
    shapeCount: Int,
    onOpen: (AllowGroup) -> Unit,
    onSwitch: (AllowGroup, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val corners = GroupShape.corners(shapeIndex, shapeCount)
    val shape = RoundedCornerShape(
        topStart = corners.topDp.dp, topEnd = corners.topDp.dp,
        bottomEnd = corners.bottomDp.dp, bottomStart = corners.bottomDp.dp,
    )
    val title = stringResource(groupTitle(row.group))
    val openLabel = stringResource(R.string.w3c_checklist_action_open)
    val count = LocalResources.current.getQuantityString(R.plurals.pt_group_count, row.count, row.count)
    Card(
        modifier = modifier.fillMaxWidth().clip(shape),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer, contentColor = colors.onSurface),
        shape = shape,
    ) {
        Row(
            Modifier.padding(end = 12.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = openLabel) { onOpen(row.group) }
                    .testTag(groupRowTag(row.group))
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(groupIcon(row.group), null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(count, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Switch(
                checked = row.on,
                onCheckedChange = { onSwitch(row.group, it) },
                modifier = Modifier.testTag(groupSwitchTag(row.group)).semantics { contentDescription = title },
            )
        }
    }
}

/**
 * The member list of one group rule (D3 A): what the group does, how many senders it has, and each sender with
 * "Remove" (undone from the snackbar). A member found by the history check says so (answer 4). Back closes it.
 */
@Composable
internal fun AllowGroupPage(
    row: AllowGroupRow,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRemove: (AllowGroupMember) -> Unit,
) {
    BackHandler(onBack = onBack)
    val title = stringResource(groupTitle(row.group))
    UMessageScaffold(
        topBar = { UMessageTopBar(title = title, onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + inner.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(GroupShape.GAP_DP.dp),
        ) {
            item(key = "body") {
                Text(
                    stringResource(groupBody(row.group)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (row.members.isEmpty()) {
                item(key = "empty") { EmptyGroup() }
            } else {
                item(key = "count") {
                    Text(
                        LocalResources.current.getQuantityString(R.plurals.pt_group_count, row.count, row.count),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
                    )
                }
                val members = row.members
                itemsIndexed(members, key = { _, member -> member.group.name + member.senderKey }) { index, member ->
                    MemberRow(member, index, members.size, onRemove)
                }
            }
        }
    }
}

/**
 * One sender of a group: the address as seen, the history label, and Remove, which TalkBack reads with the sender. At
 * large font sizes the button goes under the address, so the number is never squeezed (G-13).
 */
@Composable
private fun MemberRow(member: AllowGroupMember, index: Int, size: Int, onRemove: (AllowGroupMember) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val corners = GroupShape.corners(index, size)
    val shape = RoundedCornerShape(
        topStart = corners.topDp.dp, topEnd = corners.topDp.dp,
        bottomEnd = corners.bottomDp.dp, bottomStart = corners.bottomDp.dp,
    )
    val shown = memberLabel(LocalContext.current, member)
    val removeText = stringResource(R.string.pt_group_member_remove)
    val removeDescription = stringResource(R.string.ui_two_sentences, removeText, shown)
    val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE
    val text: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(shown, style = MaterialTheme.typography.titleMedium)
            if (member.source == MemberSource.HISTORY) {
                Text(
                    stringResource(R.string.pt_group_member_history),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
    val remove: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(
            onClick = { onRemove(member) },
            modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = removeDescription },
        ) { Text(removeText) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(shape),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer, contentColor = colors.onSurface),
        shape = shape,
    ) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 4.dp)) {
                text(Modifier.fillMaxWidth().padding(end = 12.dp))
                remove(Modifier.align(Alignment.End))
            }
        } else {
            Row(
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                text(Modifier.weight(1f))
                remove(Modifier)
            }
        }
    }
}

/** Above this font scale a member's Remove button goes under the address. */
private const val STACK_FONT_SCALE = 1.3f

/**
 * A member's address as the lists and the Remove snackbar show it: a number grouped for the phone's country and kept
 * left to right inside right-to-left text; a sender name as it is.
 */
internal fun memberLabel(context: Context, member: AllowGroupMember): String =
    isolateNumbers(displayNumber(context, member.address.ifBlank { member.senderKey }))

/** An empty group: its name of state, without an empty second line (G-13). */
@Composable
private fun EmptyGroup(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pt_group_empty),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}
