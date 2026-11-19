package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.MemberSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * D3 A: the members of the two group rules in a settings backup ([SettingsBackupFormat], entry "groupMembers").
 * Tolerant like the rest of the format: an unusable member is skipped. The member's key is not written: the restore
 * computes it on the phone that reads the file, so it follows that phone's SIM countries.
 */
internal object BackupGroupMembers {

    fun writeAll(members: List<AllowGroupMember>): JsonArray = buildJsonArray { members.forEach { add(write(it)) } }

    fun readAll(elements: List<JsonElement>): List<AllowGroupMember> = elements.mapNotNull(::read)

    private fun write(m: AllowGroupMember) = buildJsonObject {
        put("group", m.group.name)
        put("address", m.address)
        put("addedAt", m.addedAt)
        put("source", m.source.name)
        put("acknowledged", m.acknowledged)
    }

    private fun read(element: JsonElement): AllowGroupMember? {
        val o = element as? JsonObject ?: return null
        val group = AllowGroup.entries.firstOrNull { it.name == o.str("group") } ?: return null
        val address = o.str("address")?.trim().orEmpty()
        if (address.isEmpty() || address.length > SettingsBackupFormat.MAX_TARGET) return null
        val source = MemberSource.entries.firstOrNull { it.name == o.str("source") } ?: MemberSource.HISTORY
        return AllowGroupMember(
            group = group,
            senderKey = "",
            address = address,
            addedAt = o.long("addedAt") ?: 0L,
            source = source,
            acknowledged = o.bool("acknowledged") ?: true,
        )
    }
}
