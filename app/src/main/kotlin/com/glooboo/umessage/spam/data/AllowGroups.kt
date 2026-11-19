package com.glooboo.umessage.spam.data

import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.engine.GroupMembership
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.spam.engine.SenderAddress

/**
 * D3 A: the members of the two group rules, "Senders of a verification code" and "People you wrote to", in
 * spamless.db (table allow_group_members, schema 9). A member is stored under one key ([NumberReaders.senderKey]) and
 * found under any reading of its number ([NumberReaders.senderKeys]), so "+15550001234" and "(555) 000-1234" are the
 * same member on both SIMs (R6, S8).
 *
 * Membership is stored, never derived. A group that is off keeps its members (SpamSettings.groupOn). Blocking calls,
 * off the main thread; callers that change rules too run them in the same transaction.
 */
internal class AllowGroups(
    private val db: SpamDatabase,
    private val readers: NumberReaders,
    private val now: () -> Long = System::currentTimeMillis,
) : GroupMembership {

    private val dao get() = db.spamDao()

    override fun groupsOf(keys: Set<String>): Set<AllowGroup> =
        if (keys.isEmpty()) emptySet() else dao.membersByKeys(keys.toList()).map { it.group }.toSet()

    /** Every membership of the sender of [address], in either group. */
    fun membersOf(address: String): List<AllowGroupMemberEntity> {
        val keys = keysOf(address)
        return if (keys.isEmpty()) emptyList() else dao.membersByKeys(keys.toList())
    }

    /**
     * Adds the sender of [address] to [group]. False for an unreadable sender (R7). A sender already in the group keeps
     * its first row. A member from the history check is born acknowledged (answer 4). True when it is a member after.
     */
    fun join(group: AllowGroup, address: String, source: MemberSource): Boolean {
        if (SenderAddress.isUnreadable(address)) return false
        val key = readers.senderKey(address)
        if (key.isEmpty()) return false
        if (membersOf(address).any { it.group == group }) return true
        dao.insertMember(
            AllowGroupMemberEntity(
                group = group,
                senderKey = key,
                address = BlockedLimits.limitAddress(address.trim()),
                addedAt = now(),
                source = source,
                acknowledged = source == MemberSource.HISTORY,
            )
        )
        return true
    }

    /** Takes the sender of [address] out of both groups; returns the rows removed, for Undo. */
    fun leave(address: String): List<AllowGroupMemberEntity> =
        membersOf(address).onEach { dao.deleteMember(it.group, it.senderKey) }

    /** "Keep" on the chat line: the sender's memberships no longer show the line. */
    fun acknowledge(address: String) {
        val keys = keysOf(address)
        if (keys.isNotEmpty()) dao.acknowledgeMembers(keys.toList())
    }

    private fun keysOf(address: String): Set<String> = if (SenderAddress.isUnreadable(address)) {
        emptySet()
    } else {
        (readers.senderKeys(address) + readers.senderKey(address)).filterTo(HashSet()) { it.isNotEmpty() }
    }
}
