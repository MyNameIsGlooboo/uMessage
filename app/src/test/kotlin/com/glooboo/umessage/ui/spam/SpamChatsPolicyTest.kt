package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Provider truth, step C (4.2, 3.4, D1 C, U20): the rules of the Spam list. All numbers are made up. */
class SpamChatsPolicyTest {

    private val now = 1_800_000_000_000L

    private fun chat(id: Long, read: Boolean = true, unreadCount: Int = 0, snippet: String = "hi", ageMs: Long = 0L) =
        Conversation(
            threadId = id, snippet = snippet, date = ((now - ageMs) / 1000L).toInt(), read = read, title = "Chat $id",
            photoUri = "", isGroupConversation = false, phoneNumber = "+1555000123$id", unreadCount = unreadCount,
        )

    private fun spam(id: Long, cause: SpamCause, unread: Int = 0) =
        SpamThread(id, "+1555000123$id", cause, unread = unread, spamSince = 1L)

    @Test
    fun unknownSendersOfferNotSpamAndBlock() {
        val offer = SpamChatsPolicy.selection(listOf(SpamCause.UNKNOWN, SpamCause.UNKNOWN), anyUnread = true)
        assertThat(offer).isEqualTo(SpamSelection(canNotSpam = true, canBlock = true, canMarkRead = true))
    }

    @Test
    fun blockedSendersOfferUnblockOnly() {
        val offer = SpamChatsPolicy.selection(listOf(SpamCause.BLOCKED), anyUnread = false)
        assertThat(offer).isEqualTo(SpamSelection(canUnblock = true))
    }

    @Test
    fun aMixedChoiceOffersOnlyMarkAsReadBesideDelete() {
        val offer = SpamChatsPolicy.selection(listOf(SpamCause.UNKNOWN, SpamCause.BLOCKED), anyUnread = true)
        assertThat(offer).isEqualTo(SpamSelection(canMarkRead = true))
        assertThat(SpamChatsPolicy.selection(emptyList(), anyUnread = true)).isEqualTo(SpamSelection())
    }

    @Test
    fun theOneSwipeFollowsTheCause() {
        assertThat(SpamChatsPolicy.swipeUnblocks(SpamCause.BLOCKED)).isTrue()
        assertThat(SpamChatsPolicy.swipeUnblocks(SpamCause.UNKNOWN)).isFalse()
    }

    @Test
    fun theCauseLineNamesBlockedByYouOrUnknownSender() {
        assertThat(SpamChatsPolicy.causeRes(SpamCause.BLOCKED)).isEqualTo(R.string.e_note_blocked_by_you)
        assertThat(SpamChatsPolicy.causeRes(SpamCause.UNKNOWN)).isEqualTo(R.string.spam_reason_unknown_sender)
    }

    @Test
    fun aRowsUnreadAddsBothSources() {
        // 3.4: uMessage's own count plus the store's unread texts (another app wrote them).
        assertThat(SpamChat(chat(1, read = false, unreadCount = 2), spam(1, SpamCause.UNKNOWN, unread = 3)).unread)
            .isEqualTo(5)
        assertThat(SpamChat(chat(1, read = false, unreadCount = 0), spam(1, SpamCause.UNKNOWN)).unread).isEqualTo(1)
        assertThat(SpamChat(chat(1), spam(1, SpamCause.UNKNOWN)).unread).isEqualTo(0)
    }

    @Test
    fun theTabNumberCountsUnknownSendersWithNewUnreadTextsOnly() {
        val seenAt = now - 60_000L
        val chats = listOf(
            SpamChat(chat(1, ageMs = 1_000L), spam(1, SpamCause.UNKNOWN, unread = 1)),
            // A blocked sender never counts.
            SpamChat(chat(2, ageMs = 1_000L), spam(2, SpamCause.BLOCKED, unread = 4)),
            // Looked at before the tab was last open: the tab number is clear, the row stays bold.
            SpamChat(chat(3, ageMs = 120_000L), spam(3, SpamCause.UNKNOWN, unread = 1)),
            SpamChat(chat(4, ageMs = 1_000L), spam(4, SpamCause.UNKNOWN)),
        )
        assertThat(SpamChatsPolicy.tabCount(chats, seenAt)).isEqualTo(1)
        // Opening the tab clears it.
        assertThat(SpamChatsPolicy.tabCount(chats, now)).isEqualTo(0)
    }

    /** The newest received text, [ageMs] old (body, date in seconds). */
    private fun received(body: String, ageMs: Long) = body to ((now - ageMs) / 1000L).toInt()

    @Test
    fun copyCodeShowsForTenMinutesReadOrNot() {
        val extract: (String) -> String? = { if (it.contains("4821")) "4821" else null }
        assertThat(SpamChatsPolicy.code(received("Your code is 4821", 60_000L), now, extract)).isEqualTo("4821")
        assertThat(SpamChatsPolicy.code(received("Your code is 4821", 11 * 60_000L), now, extract)).isNull()
        assertThat(SpamChatsPolicy.code(received("Sale today", 0L), now, extract)).isNull()
        // C-14: no received text (only a sent one is the newest), no code.
        assertThat(SpamChatsPolicy.code(null, now, extract)).isNull()
    }

    @Test
    fun theCodeEndsTenMinutesAfterItArrivedAndOnlyNewChatsAreRead() {
        val text = received("Your code is 4821", 60_000L)
        assertThat(SpamChatsPolicy.codeEndsAt(text)).isEqualTo(text.second * 1000L + SpamChatsPolicy.CODE_MS)
        assertThat(SpamChatsPolicy.mayHoldFreshCode(chat(1, ageMs = 60_000L), now)).isTrue()
        assertThat(SpamChatsPolicy.mayHoldFreshCode(chat(1, ageMs = 11 * 60_000L), now)).isFalse()
    }

    @Test
    fun copyCodeUsesTheRealExtractorForAWeakCodeToo() {
        // Owner decision of 20 Nov: a weak code stays in Spam, and its row still offers Copy code.
        val code = SpamChatsPolicy.code(received("Your code: 739204", 0L), now)
        assertThat(code).isEqualTo("739204")
    }

    @Test
    fun aSenderOnAndroidsBlockListShowsItsOwnLine() {
        assertThat(SpamChatsPolicy.causeRes(SpamCause.BLOCKED, androidListed = true))
            .isEqualTo(R.string.e_note_system_list)
    }

    @Test
    fun olderSpamListsOnlyTextsNotMovedYetNewestFirst() {
        fun held(id: Long, at: Long, moved: Long? = null) = BlockedMessage(
            id = id, address = "+15550001234", body = "text $id", subscriptionId = 1, isMms = false,
            receivedAt = at, reason = DecisionReason.UNKNOWN_SENDER, migratedMsgId = moved,
        )
        val older = OlderSpam.of(listOf(held(1, 10L), held(2, 30L), held(3, 20L, moved = 99L)))
        assertThat(older.map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}
