package com.glooboo.umessage.spam

import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.FreezableBlocklist
import com.glooboo.umessage.spam.engine.GroupMembership
import com.glooboo.umessage.spam.engine.LegacyBlocklist
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.spam.engine.SenderJudge

/**
 * The sender facts of the sorting tests in memory: rules, group members, contacts and the settings, with the real
 * [SenderJudge] on top. Every number is made up (the 555 range).
 */
internal class SortingWorld(region: String = "US") {
    val normalizer = NumberNormalizer { region }
    val readers = NumberReaders(normalizer)
    val rules = mutableListOf<SpamRule>()
    val members = mutableListOf<AllowGroupMember>()
    val contacts = mutableSetOf<String>()
    var settings = SpamSettings()
    var contactsReadable = true
    var lookupFails = false
    var rulesFail = false
    var snapshots = 0
    private var nextId = 100L

    fun block(pattern: String, type: RuleType = RuleType.NUMBER): Long = rule(ListKind.BLOCK, type, pattern)

    fun allow(pattern: String, type: RuleType = RuleType.NUMBER): Long = rule(ListKind.ALLOW, type, pattern)

    fun rule(kind: ListKind, type: RuleType, pattern: String, note: String = ""): Long {
        val id = nextId++
        rules += SpamRule(id = id, kind = kind, type = type, pattern = pattern, note = note)
        return id
    }

    fun member(group: AllowGroup, address: String) {
        members += AllowGroupMember(group, readers.senderKey(address), address, source = MemberSource.LIVE)
    }

    /** The keys of [contacts], read again only when the set changed (the 5,000-chat test asks for every chat). */
    private var contactKeys: Pair<Set<String>, Set<String>> = emptySet<String>() to emptySet()

    private fun contactKeys(): Set<String> {
        val now = contacts.toSet()
        if (contactKeys.first != now) contactKeys = now to now.flatMapTo(HashSet()) { readers.senderKeys(it) }
        return contactKeys.second
    }

    /** Android's own block list; [blockListReads] counts every full read of it (B-2). */
    val androidBlocked = mutableSetOf<String>()
    var blockListReads = 0

    /** Android's block list of this world: every check reads it once, as the real one does (B-2). */
    inner class AndroidBlockList : LegacyBlocklist, FreezableBlocklist {
        override fun isNumberBlocked(address: String): Boolean = frozenNumberCheck()(address)
        override fun blockedKeywords(): Collection<String> = emptyList()
        override fun blockUnknownNumbers() = false
        override fun frozenNumberCheck(): (String) -> Boolean {
            blockListReads++
            val keys = androidBlocked.flatMapTo(HashSet()) { readers.senderKeys(it) }
            return { address -> readers.senderKeys(address).any { it in keys } }
        }
    }

    val legacy: AndroidBlockList = AndroidBlockList()

    val checker = object : ContactChecker {
        override fun isContact(address: String) = readers.senderKeys(address).any { it in contactKeys() }
        override fun lookup(address: String): ContactLookup = when {
            lookupFails -> ContactLookup.UNKNOWN
            isContact(address) -> ContactLookup.FOUND
            else -> ContactLookup.NOT_FOUND
        }
        override fun canReadContacts() = contactsReadable
    }

    val judge = SenderJudge(
        rules = {
            snapshots++
            check(!rulesFail) { "rules unreadable" }
            rules.toList()
        },
        settings = { settings },
        contacts = checker,
        readers = readers,
        groups = GroupMembership { members.toList() },
        legacy = legacy,
        matcher = RuleMatcher(normalizer),
    )

    companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val CONTACT = "+15550001236"
        const val BLOCKED = "+15550001237"
        const val ALLOWED = "+15550001238"
        const val MEMBER = "+15550001239"

        fun chat(
            threadId: Long,
            number: String,
            group: Boolean = false,
            archived: Boolean = false,
            scheduled: Boolean = false,
        ) = Conversation(
            threadId = threadId, snippet = "hello", date = threadId.toInt(), read = true, title = "Chat $threadId",
            photoUri = "", isGroupConversation = group, phoneNumber = number, isScheduled = scheduled,
            isArchived = archived,
        )
    }
}
