package com.glooboo.umessage.spam.data

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
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

    override fun members(): List<AllowGroupMember> = dao.getMembers().map { it.toModel() }

    /** The groups the sender of [address] is in. */
    fun groupsOf(address: String): Set<AllowGroup> = membersOf(address).map { it.group }.toSet()

    /**
     * Every membership of the sender of [address], in either group: by key, and else (A-6) by the address each member
     * was stored with, read with today's SIM list, so a member stored under an older list is still found.
     */
    fun membersOf(address: String): List<AllowGroupMemberEntity> {
        val keys = keysOf(address)
        if (keys.isEmpty()) return emptyList()
        val byKey = dao.membersByKeys(keys.toList())
        if (byKey.isNotEmpty()) return byKey
        return dao.getMembers().filter { readers.same(it.address, address) }
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

    /** Every key the sender of [address] may be stored under (also for [RemovedMembers]). */
    fun keysOf(address: String): Set<String> = if (SenderAddress.isUnreadable(address)) {
        emptySet()
    } else {
        (readers.senderKeys(address) + readers.senderKey(address)).filterTo(HashSet()) { it.isNotEmpty() }
    }
}

/**
 * B-9: the senders the user took out of a group with "Remove". The history check never puts them back (a new code or
 * a reply still does, and clears the mark). Kept in the app's preferences once [install]ed at app start, in memory
 * before (tests). Keys are "<group>|<sender key>", every reading of the sender.
 */
object RemovedMembers {
    private const val KEY = "removed"
    private val memory = HashSet<String>()

    @Volatile private var prefs: SharedPreferences? = null

    /** New in provider truth; not a `spamless_` name. Never rename. */
    const val PREFS = "umessage_group_removed"

    fun install(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    /** Forgets every mark and the installed preferences (tests). */
    @VisibleForTesting
    @Synchronized
    internal fun reset() {
        prefs = null
        memory.clear()
    }

    @Synchronized
    fun add(group: AllowGroup, keys: Collection<String>) = write { it + keys.map { key -> entry(group, key) } }

    @Synchronized
    fun remove(group: AllowGroup, keys: Collection<String>) =
        write { it - keys.map { key -> entry(group, key) }.toSet() }

    @Synchronized
    fun contains(group: AllowGroup, keys: Collection<String>): Boolean {
        val stored = read()
        return keys.any { entry(group, it) in stored }
    }

    private fun entry(group: AllowGroup, key: String) = "${group.name}|$key"

    private fun read(): Set<String> {
        val store = prefs ?: return memory.toSet()
        return store.getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    private fun write(change: (Set<String>) -> Set<String>) {
        val next = change(read())
        val store = prefs
        if (store == null) {
            memory.clear()
            memory += next
        } else {
            store.edit(commit = true) { putStringSet(KEY, HashSet(next)) }
        }
    }
}
