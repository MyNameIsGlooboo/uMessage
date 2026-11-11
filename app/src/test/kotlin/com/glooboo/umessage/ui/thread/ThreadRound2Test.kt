package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Search over older messages, the lookup cache and the new-message count of the chat screen. */
class ThreadRound2Test {

    private fun hit(id: Long, date: Int) = SearchHit(MessageKey(id, false), date)

    @Test
    fun olderMatchesJoinTheLoadedOnesNewestFirstWithoutDuplicates() {
        val loaded = listOf(hit(9, 90), hit(8, 80))
        val older = listOf(hit(8, 80), hit(3, 30), hit(1, 10))
        assertThat(mergeSearchHits(loaded, older))
            .containsExactly(MessageKey(9, false), MessageKey(8, false), MessageKey(3, false), MessageKey(1, false))
            .inOrder()
        assertThat(mergeSearchHits(loaded, emptyList()))
            .containsExactly(MessageKey(9, false), MessageKey(8, false)).inOrder()
        assertThat(mergeSearchHits(emptyList(), older)).hasSize(3)
    }

    @Test
    fun smsAndMmsWithTheSameIdAreDifferentMatches() {
        val merged = mergeSearchHits(listOf(hit(5, 50)), listOf(SearchHit(MessageKey(5, true), 40)))
        assertThat(merged).containsExactly(MessageKey(5, false), MessageKey(5, true)).inOrder()
    }

    @Test
    fun lookupsRunOnceUntilInvalidated() {
        val cache = ThreadLookupCache()
        var calls = 0
        repeat(3) { cache.isSaved("+100") { calls++; true } }
        assertThat(calls).isEqualTo(1)
        cache.isSaved("+200") { calls++; false }
        assertThat(calls).isEqualTo(2)
        cache.invalidateContacts()
        cache.isSaved("+200") { calls++; false }
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun strangerIsAskedAgainOnceTheUserWritesToThem() {
        val cache = ThreadLookupCache()
        var calls = 0
        assertThat(cache.isStranger("+1", false, false) { calls++; true }).isTrue()
        assertThat(cache.isStranger("+1", false, false) { calls++; true }).isTrue()
        assertThat(calls).isEqualTo(1)
        assertThat(cache.isStranger("+1", false, true) { calls++; false }).isFalse()
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun simResultIsKeptUntilTheSimsChange() {
        val cache = ThreadLookupCache()
        var calls = 0
        repeat(2) { cache.sims { calls++; "labels" } }
        assertThat(calls).isEqualTo(1)
        cache.invalidateSims()
        cache.sims { calls++; "labels" }
        assertThat(calls).isEqualTo(2)
    }

    private fun message(id: Long, body: String = "x") = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = id.toInt(), read = true, threadId = 1L, isMMS = false, attachment = null,
        senderPhoneNumber = "1", senderName = "1", senderPhotoUri = "", subscriptionId = -1,
    )

    @Test
    fun onlyNewOrChangedMessagesAreWrittenBack() {
        val cached = listOf(message(1), message(2))
        val fresh = listOf(message(1), message(2, "edited"), message(3))
        assertThat(messagesToCache(fresh, cached).map { it.id }).containsExactly(2L, 3L)
        assertThat(messagesToCache(fresh, emptyList())).hasSize(3)
        assertThat(messagesToCache(cached, cached)).isEmpty()
    }

    @Test
    fun newMessageCountCountsRowsAboveTheOnesSeenWhileScrolledUp() {
        val keys = listOf("c", "b", "a")
        assertThat(newRowsSince(keys, "b")).isEqualTo(1)
        assertThat(newRowsSince(keys, "c")).isEqualTo(0)
        assertThat(newRowsSince(keys, "gone")).isEqualTo(0)
        assertThat(newRowsSince(emptyList(), "b")).isEqualTo(0)
        assertThat(newRowsSince(keys, null)).isEqualTo(0)
        // Only counted rows (received messages) add up: a date header or the user's own message does not.
        assertThat(newRowsSince(listOf("d", "h", "m"), "m") { it != "h" }).isEqualTo(1)
    }
}
