package com.glooboo.umessage.spam

import com.glooboo.umessage.spam.SortingWorld.Companion.BLOCKED
import com.glooboo.umessage.spam.SortingWorld.Companion.CONTACT
import com.glooboo.umessage.spam.SortingWorld.Companion.MEMBER
import com.glooboo.umessage.spam.SortingWorld.Companion.OTHER
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.engine.AllowingCode
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Step B, the history check (3.5: R2, U3, R5): unknown senders the user wrote to join "People you wrote to", unknown
 * senders of an old verification code join the code group, everyone else is left for the sort. Numbers and texts are
 * made up.
 */
class HistoryCheckTest {

    private val world = SortingWorld()

    /** The phone's store in memory: chats, the numbers written to, and received texts per thread. */
    private inner class Store : HistoryStore {
        val chats = mutableListOf<SortableChat>()
        val wrote = mutableSetOf<String>()
        val received = mutableMapOf<Long, MutableList<String>>()
        var chatsFail = false
        var sentFails = false
        var textsFail = false

        fun chat(threadId: Long, number: String, group: Boolean = false, vararg texts: String) {
            chats += SortableChat(threadId, number, isGroup = group, isScheduled = false)
            received.getOrPut(threadId) { mutableListOf() } += texts
        }

        /** Threads of the phone's store the app has not cached yet (B-5). */
        val uncached = mutableSetOf<Long>()

        override fun chats(): List<SortableChat>? = chats.toList().takeUnless { chatsFail }
        override fun cachedChats(): List<SortableChat> = chats.filter { it.threadId !in uncached }
        override fun wroteTo(address: String): ContactLookup = when {
            sentFails -> ContactLookup.UNKNOWN
            wrote.any { world.readers.same(it, address) } -> ContactLookup.FOUND
            else -> ContactLookup.NOT_FOUND
        }
        override fun threadsWithReceived(threadIds: Collection<Long>, matches: (String) -> Boolean): Set<Long>? =
            if (textsFail) null else threadIds.filterTo(HashSet()) { id -> received[id].orEmpty().any(matches) }
    }

    private val store = Store()
    private val joined = mutableListOf<Pair<AllowGroup, List<String>>>()

    private val check = HistoryCheck(
        store = store,
        snapshot = { world.judge.snapshot() },
        join = { group, senders ->
            joined += group to senders
            senders.forEach { world.member(group, it) }
            senders.size
        },
    )

    private fun groupOf(address: String): AllowGroup? = world.members.firstOrNull {
        world.readers.same(it.address, address)
    }?.group

    @Test
    fun aNumberTheUserWroteToJoinsPeopleYouWroteTo() {
        store.chat(1, SENDER)
        store.wrote += "(555) 000-1234"

        val result = check.run()

        assertThat(result).isEqualTo(HistoryResult(wrote = 1, code = 0))
        assertThat(groupOf(SENDER)).isEqualTo(AllowGroup.WROTE)
        assertThat(world.judge.verdict(SENDER)).isInstanceOf(SenderVerdict.Messages::class.java)
    }

    @Test
    fun aSenderOfAnOldVerificationCodeJoinsTheCodeGroup() {
        store.chat(1, SENDER, false, "Hello", "Your ExampleBank code is 573920. Never share it.")

        val result = HistoryCheck(store, { world.judge.snapshot() }, { group, senders ->
            senders.forEach { world.member(group, it) }
            senders.size
        }).run()

        assertThat(result?.code).isEqualTo(1)
        assertThat(groupOf(SENDER)).isEqualTo(AllowGroup.CODE)
    }

    @Test
    fun aWeakCodeAllowsNobody() {
        // 20 Nov (B): a weak code stays silent in Spam and the sender is not allowed.
        assertThat(AllowingCode.isAllowingCode("Use 664422 now")).isFalse()
        store.chat(1, SENDER, false, "Use 664422 now")

        val result = HistoryCheck(store, { world.judge.snapshot() }, { _, senders -> senders.size }).run()

        assertThat(result).isEqualTo(HistoryResult(0, 0))
    }

    @Test
    fun writingWinsOverACode() {
        store.chat(1, SENDER, false, "Your ExampleBank code is 573920. Never share it.")
        store.wrote += SENDER

        check.run()

        assertThat(joined).containsExactly(AllowGroup.WROTE to listOf(SENDER))
    }

    @Test
    fun knownBlockedAndGroupChatsAreNeverCandidates() {
        world.block(BLOCKED)
        world.contacts += CONTACT
        world.member(AllowGroup.CODE, MEMBER)
        store.chat(1, BLOCKED)
        store.chat(2, CONTACT)
        store.chat(3, MEMBER)
        store.chat(4, OTHER, group = true)
        store.chat(5, "Unknown")
        store.wrote += listOf(BLOCKED, CONTACT, MEMBER, OTHER, "Unknown")

        val result = check.run()

        assertThat(result).isEqualTo(HistoryResult(0, 0))
        assertThat(joined).isEmpty()
    }

    @Test
    fun withTheFilterOffUnknownSendersAreStillChecked() {
        // Answer 3: memberships are made also while the filter is off.
        world.settings = world.settings.copy(filterOn = false)
        store.chat(1, SENDER)
        store.wrote += SENDER

        assertThat(check.run()).isEqualTo(HistoryResult(1, 0))
    }

    @Test
    fun aFailedReadStopsTheCheckWithoutJoining() {
        store.chat(1, SENDER, false, "Your ExampleBank code is 573920. Never share it.")
        store.chat(2, OTHER)

        store.chatsFail = true
        assertThat(check.run()).isNull()
        store.chatsFail = false

        store.sentFails = true
        assertThat(check.run()).isNull()
        store.sentFails = false

        store.textsFail = true
        assertThat(check.run()).isNull()
        assertThat(joined).isEmpty()

        world.rulesFail = true
        store.textsFail = false
        assertThat(check.run()).isNull()
        assertThat(joined).isEmpty()
    }

    @Test
    fun manySendersJoinInOneBatchPerGroup() {
        // R-3: bulk joinGroups, one call per group.
        repeat(30) { store.chat(it + 1L, "+1555000${3000 + it}") }
        repeat(10) { store.wrote += "+1555000${3000 + it}" }
        repeat(5) { store.received.getValue(20L + it) += "Your ExampleBank code is 573920. Never share it." }

        val result = HistoryCheck(store, { world.judge.snapshot() }, { group, senders ->
            joined += group to senders
            senders.size
        }).run()

        assertThat(result).isEqualTo(HistoryResult(wrote = 10, code = 5))
        assertThat(joined.map { it.first }).containsExactly(AllowGroup.WROTE, AllowGroup.CODE)
    }

    @Test
    fun anSmsAsTheFirstEventAfterTheUpdateFindsTheOldSentText() {
        // R2: the whole check has not run yet; the sender of the arriving text is checked on the spot.
        store.chat(1, SENDER)
        store.wrote += SENDER
        assertThat(world.judge.verdict(SENDER)).isInstanceOf(SenderVerdict.Spam::class.java)

        assertThat(check.checkSender(SENDER)).isTrue()

        assertThat(groupOf(SENDER)).isEqualTo(AllowGroup.WROTE)
        assertThat(world.judge.verdict(SENDER)).isInstanceOf(SenderVerdict.Messages::class.java)
    }

    @Test
    fun theSpotCheckFindsAnOldCodeInTheSendersCachedChat() {
        store.chat(1, "(555) 000-1234", false, "Your ExampleBank code is 573920. Never share it.")

        assertThat(check.checkSender(SENDER)).isTrue()

        assertThat(joined).containsExactly(AllowGroup.CODE to listOf(SENDER))
    }

    @Test
    fun theSpotCheckReadsTheTextsOwnThreadEvenWhenItIsNotCached() {
        // B-5: a fresh install, before the list cached the chat: the store's thread of the text still counts.
        store.chat(7, SENDER, false, "Your ExampleBank code is 573920. Never share it.")
        store.uncached += 7L

        assertThat(check.checkSender(SENDER)).isTrue()
        assertThat(joined).isEmpty()

        assertThat(check.checkSender(SENDER, threadId = 7L)).isTrue()
        assertThat(joined).containsExactly(AllowGroup.CODE to listOf(SENDER))
    }

    @Test
    fun theSpotCheckLeavesOthersAloneAndSaysWhenItCouldNotRead() {
        world.block(BLOCKED)
        assertThat(check.checkSender(BLOCKED)).isTrue()
        assertThat(check.checkSender(OTHER)).isTrue()
        assertThat(joined).isEmpty()

        store.sentFails = true
        assertThat(check.checkSender(OTHER)).isFalse()
        store.sentFails = false
        store.chat(2, OTHER, false, "hi")
        store.textsFail = true
        assertThat(check.checkSender(OTHER)).isFalse()
    }
}
