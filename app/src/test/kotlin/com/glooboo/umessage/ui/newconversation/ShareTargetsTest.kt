package com.glooboo.umessage.ui.newconversation

import com.glooboo.umessage.models.Conversation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareTargetsTest {

    private fun chat(
        id: Long,
        title: String,
        number: String,
        date: Int,
        group: Boolean = false,
        archived: Boolean = false,
        scheduled: Boolean = false,
    ) = Conversation(
        threadId = id,
        snippet = "hello",
        date = date,
        read = true,
        title = title,
        photoUri = "",
        isGroupConversation = group,
        phoneNumber = number,
        isScheduled = scheduled,
        isArchived = archived,
    )

    private val sara = chat(1, "Sara Ahmadi", "+15550001234", date = 100)
    private val tom = chat(2, "Tom", "+15550005678", date = 300)
    private val team = chat(3, "Tom, Sara", "+15550005678", date = 200, group = true)
    private val unknown = chat(4, "+447100000001", "+447100000001", date = 50)

    @Test
    fun `recent chats are newest first and groups are included`() {
        val result = recentShareTargets(listOf(sara, tom, team, unknown))
        assertThat(result.map { it.threadId }).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    @Test
    fun `at most eight chats are offered`() {
        val many = (1L..12L).map { chat(it, "Chat $it", "+1555000${1000 + it}", date = it.toInt()) }
        val result = recentShareTargets(many)
        assertThat(result).hasSize(RECENT_SHARE_LIMIT)
        assertThat(result.first().threadId).isEqualTo(12L)
        assertThat(result.last().threadId).isEqualTo(5L)
        assertThat(recentShareTargets(many, limit = 3)).hasSize(3)
    }

    @Test
    fun `equal dates keep a stable order`() {
        val a = chat(10, "A", "+15550000010", date = 5)
        val b = chat(11, "B", "+15550000011", date = 5)
        assertThat(recentShareTargets(listOf(a, b)).map { it.threadId }).containsExactly(11L, 10L).inOrder()
        assertThat(recentShareTargets(listOf(b, a)).map { it.threadId }).containsExactly(11L, 10L).inOrder()
    }

    @Test
    fun `archived, scheduled placeholder and addressless chats are left out`() {
        val archived = chat(5, "Old", "+15550000005", date = 900, archived = true)
        val placeholder = chat(6, "Later", "+15550000006", date = 800, scheduled = true)
        val noAddress = chat(7, "Nobody", " ", date = 700)
        val result = recentShareTargets(listOf(archived, placeholder, noAddress, sara))
        assertThat(result).containsExactly(sara)
    }

    @Test
    fun `no conversations give no recent chats`() {
        assertThat(recentShareTargets(emptyList())).isEmpty()
        assertThat(filterShareTargets(emptyList(), "sara")).isEmpty()
    }

    @Test
    fun `a blank query is the recent list`() {
        val all = listOf(sara, tom, team)
        assertThat(filterShareTargets(all, "  ")).isEqualTo(recentShareTargets(all))
    }

    @Test
    fun `search matches the title ignoring case`() {
        val result = filterShareTargets(listOf(sara, tom, team, unknown), "SARA")
        assertThat(result.map { it.threadId }).containsExactly(3L, 1L).inOrder()
    }

    @Test
    fun `search matches a typed number however it is written`() {
        val all = listOf(sara, tom, team, unknown)
        assertThat(filterShareTargets(all, "555 0001 234")).containsExactly(sara)
        assertThat(filterShareTargets(all, "+1 555 000 5678").map { it.threadId }).containsExactly(2L, 3L).inOrder()
        // A national form with a trunk zero finds the international number.
        assertThat(filterShareTargets(all, "07100000001")).containsExactly(unknown)
    }

    @Test
    fun `search reads Persian digits`() {
        val all = listOf(sara, tom)
        assertThat(filterShareTargets(all, "۵۵۵۰۰۰۱۲۳۴")).containsExactly(sara)
    }

    @Test
    fun `search folds Arabic letter variants in the title`() {
        val ali = chat(20, "علی رضایی", "+15550000020", date = 10)
        assertThat(filterShareTargets(listOf(ali, sara), "علي")).containsExactly(ali)
    }

    @Test
    fun `search never offers excluded chats and an unmatched query hides the section`() {
        val archived = chat(5, "Sara Old", "+15550000005", date = 900, archived = true)
        assertThat(filterShareTargets(listOf(archived, sara), "sara")).containsExactly(sara)
        assertThat(filterShareTargets(listOf(sara, tom), "zzz")).isEmpty()
    }

    @Test
    fun `a name query does not match numbers by accident`() {
        // "ali" is in neither title nor number, so nothing matches; a real name part finds its chat.
        assertThat(filterShareTargets(listOf(sara, tom), "ali")).isEmpty()
        assertThat(filterShareTargets(listOf(sara, tom), "ahmad")).containsExactly(sara)
    }
}
