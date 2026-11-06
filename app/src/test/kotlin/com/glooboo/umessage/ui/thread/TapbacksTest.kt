package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapbacksTest {

    private val open = "“"
    private val close = "”"

    private fun q(verb: String, text: String) = "$verb $open$text$close"

    private fun message(id: Long, body: String, received: Boolean = true) = Message(
        id = id, body = body,
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0, participants = ArrayList(), date = id.toInt() * 10, read = true, threadId = 1L, isMMS = false,
        attachment = null, senderPhoneNumber = "+1", senderName = "+1", senderPhotoUri = "", subscriptionId = 1,
    )

    // region parser
    @Test
    fun everyKindIsRecognised() {
        val expected = mapOf(
            "Liked" to TapbackKind.Liked, "Loved" to TapbackKind.Loved, "Disliked" to TapbackKind.Disliked,
            "Laughed at" to TapbackKind.Laughed, "Emphasized" to TapbackKind.Emphasized,
            "Questioned" to TapbackKind.Questioned,
        )
        expected.forEach { (verb, kind) ->
            assertThat(parseTapback(q(verb, "See you at 8"))).isEqualTo(Tapback(kind, "See you at 8", false))
        }
    }

    @Test
    fun straightQuotesWork() {
        assertThat(parseTapback("Liked \"ok\"")).isEqualTo(Tapback(TapbackKind.Liked, "ok", false))
    }

    @Test
    fun aLongOriginalEndsWithAnEllipsis() {
        assertThat(parseTapback(q("Loved", "Dinner is at the new place near…")))
            .isEqualTo(Tapback(TapbackKind.Loved, "Dinner is at the new place near", true))
        assertThat(parseTapback(q("Loved", "Dinner is...")))
            .isEqualTo(Tapback(TapbackKind.Loved, "Dinner is", true))
    }

    @Test
    fun mixedDirectionAndPersianOriginalsKeepTheirText() {
        val original = "سلام see you ۱۲۳"
        assertThat(parseTapback(q("Laughed at", original))?.quoted).isEqualTo(original)
    }

    @Test
    fun quotesInsideTheOriginalStayInside() {
        val parsed = parseTapback("Liked ${open}He said ${open}hi$close$close")
        assertThat(parsed?.quoted).isEqualTo("He said ${open}hi$close")
    }

    @Test
    fun surroundingSpacesAreIgnored() {
        assertThat(parseTapback("  " + q("Liked", "ok") + "\n")?.quoted).isEqualTo("ok")
    }

    @Test
    fun textsThatAreNotReactionsAreLeftAlone() {
        listOf(
            "", "Liked", "Liked it", "Liked an image", "Liked a photo", "Loved a movie", "I Liked ${open}x$close",
            "liked ${open}x$close", "Liked  ${open}x$close", "Liked ${open}x$close thanks",
            "Liked ${open}x", "Liked x$close",
            "Liked $open$close", "Liked $open $close", "Disliked", "Emphasized an attachment", "Questioned $open",
            "Removed a like from ${open}x$close", "Thanks, liked ${open}x$close", "Like ${open}x$close",
            "Laughed ${open}x$close", "Love ${open}x$close", "دوس داشت ${open}x$close",
        ).forEach { assertThat(parseTapback(it)).isNull() }
    }
    // endregion

    // region matching and applying
    @Test
    fun aReactionCountsOnTheMessageItNamesAndLeavesTheList() {
        val target = message(1, "See you at 8", received = false)
        val reaction = message(2, q("Liked", "See you at 8"))
        val result = applyTapbacks(listOf(target, reaction))
        assertThat(result.hidden).containsExactly(reaction.getStableId())
        assertThat(result.reactions[target.getStableId()])
            .containsExactly(TapbackReaction(TapbackKind.Liked, 1, listOf(q("Liked", "See you at 8"))))
    }

    @Test
    fun severalReactionsOnOneMessageAreCountedPerKind() {
        val target = message(1, "Party tonight")
        val list = listOf(
            target,
            message(2, q("Liked", "Party tonight")),
            message(3, q("Liked", "Party tonight")),
            message(4, q("Loved", "Party tonight")),
        )
        val reactions = applyTapbacks(list).reactions.getValue(target.getStableId())
        assertThat(reactions.map { it.kind to it.count })
            .containsExactly(TapbackKind.Liked to 2, TapbackKind.Loved to 1)
    }

    @Test
    fun anEllipsizedQuoteMatchesThePrefix() {
        val target = message(1, "Dinner is at the new place near the station at seven")
        val reaction = message(2, q("Liked", "Dinner is at the new place near…"))
        assertThat(applyTapbacks(listOf(target, reaction)).hidden).hasSize(1)
    }

    @Test
    fun caseAndSpacingAreFoldedAsALastResort() {
        val target = message(1, "See  you   AT 8")
        val reaction = message(2, q("Liked", "see you at 8"))
        assertThat(applyTapbacks(listOf(target, reaction)).hidden).hasSize(1)
    }

    @Test
    fun theNewestMatchingMessageWins() {
        val first = message(1, "ok")
        val second = message(2, "ok")
        val reaction = message(3, q("Liked", "ok"))
        val result = applyTapbacks(listOf(first, second, reaction))
        assertThat(result.reactions.keys).containsExactly(second.getStableId())
    }

    @Test
    fun aReactionWithoutAnEarlierTargetStaysVisible() {
        val reaction = message(2, q("Liked", "Something deleted"))
        val other = message(1, "Hello")
        assertThat(applyTapbacks(listOf(other, reaction))).isEqualTo(TapbackResult.None)
        assertThat(applyTapbacks(listOf(reaction, message(3, "Something deleted")))).isEqualTo(TapbackResult.None)
    }

    @Test
    fun likedAnImageHasNoTargetAndStays() {
        val list = listOf(message(1, "Hello"), message(2, "Liked an image"))
        assertThat(applyTapbacks(list)).isEqualTo(TapbackResult.None)
    }

    @Test
    fun sentTextsThatLookLikeReactionsAreNeverHidden() {
        val list = listOf(message(1, "ok"), message(2, q("Liked", "ok"), received = false))
        assertThat(applyTapbacks(list)).isEqualTo(TapbackResult.None)
    }

    @Test
    fun aReactionDoesNotReactToAReaction() {
        val list = listOf(
            message(1, "ok"),
            message(2, q("Liked", "ok")),
            message(3, q("Liked", q("Liked", "ok"))),
        )
        assertThat(applyTapbacks(list).hidden).hasSize(1)
    }
    // endregion

    // region builder
    private fun items(list: List<Message>, show: Boolean) =
        buildThreadItems(list, emptyMap(), seen = false, showTapbacks = show)

    @Test
    fun switchedOffTheListIsUnchanged() {
        val list = listOf(message(1, "ok"), message(2, q("Liked", "ok")))
        val result = items(list, show = false)
        assertThat(result.items.filterIsInstance<Message>()).hasSize(2)
        assertThat(result.reactions).isEmpty()
    }

    @Test
    fun switchedOnTheReactionLeavesTheListAndIsMarkedReadWhenSeen() {
        val list = listOf(message(1, "ok"), message(2, q("Liked", "ok")).copy(read = false))
        val shown = items(list, show = true)
        assertThat(shown.items.filterIsInstance<Message>().map { it.id }).containsExactly(1L)
        assertThat(shown.reactions).hasSize(1)
        val seen = buildThreadItems(list, emptyMap(), seen = true, showTapbacks = true)
        assertThat(seen.newlyRead.map { it.id }).containsExactly(2L)
    }

    @Test
    fun theUnreadLineMovesPastAHiddenReaction() {
        val list = listOf(
            message(1, "ok"),
            message(2, q("Liked", "ok")).copy(read = false),
            message(3, "next").copy(read = false),
        )
        val anchor = firstUnreadMessageId(list)
        val result = buildThreadItems(list, emptyMap(), seen = false, firstUnreadId = anchor, showTapbacks = true)
        val order = result.items.filter { it is Message || it is ThreadItem.ThreadUnreadDivider }.map { it::class }
        assertThat(order)
            .containsExactly(Message::class, ThreadItem.ThreadUnreadDivider::class, Message::class)
            .inOrder()
    }
    // endregion
}
