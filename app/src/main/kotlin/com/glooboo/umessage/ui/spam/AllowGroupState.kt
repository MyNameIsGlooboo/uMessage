package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.SpamSettings

/**
 * D3 A: one group rule as the Rules list shows it, "Senders of a verification code" or "People you wrote to": its
 * switch and its members, newest first.
 */
data class AllowGroupRow(
    val group: AllowGroup,
    val on: Boolean,
    val members: List<AllowGroupMember>,
) {
    val count: Int get() = members.size
}

/**
 * The group rules part of the Rules screen. [shown] is false until the groups are read.
 * [open] is the group whose member list is showing; [confirm] the group switch waiting for its confirm.
 */
data class AllowGroupsState(
    val shown: Boolean = false,
    val rows: List<AllowGroupRow> = emptyList(),
    val open: AllowGroup? = null,
    val confirm: SwitchConfirm? = null,
) {
    /** The row of the open member list, or null when none is open. */
    val openRow: AllowGroupRow? get() = rows.firstOrNull { it.group == open }

    companion object {
        /** Both groups, code group first, each with its own members and switch. */
        fun of(
            members: List<AllowGroupMember>,
            settings: SpamSettings,
            open: AllowGroup?,
            confirm: SwitchConfirm?,
        ): AllowGroupsState {
            val rows = AllowGroup.entries.map { group ->
                AllowGroupRow(
                    group = group,
                    on = settings.groupOn(group),
                    members = members.filter { it.group == group }.sortedByDescending { it.addedAt },
                )
            }
            return AllowGroupsState(shown = true, rows = rows, open = open, confirm = confirm)
        }
    }
}
