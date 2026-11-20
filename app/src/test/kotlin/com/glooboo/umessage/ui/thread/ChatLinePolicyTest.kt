package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Provider truth, step C (4.3, U10; step N item 3): the six lines of the chat screen and their buttons. */
class ChatLinePolicyTest {

    private val on = SpamSettings()

    private fun rule(id: Long, kind: ListKind, note: String, acknowledged: Boolean = false) = SpamRule(
        id = id, kind = kind, type = RuleType.NUMBER, pattern = SENDER, note = note, acknowledged = acknowledged,
    )

    private fun member(group: AllowGroup, acknowledged: Boolean = false, source: MemberSource = MemberSource.LIVE) =
        AllowGroupMember(group, SENDER, SENDER, source = source, acknowledged = acknowledged)

    private val groupMember = SenderVerdict.Messages(DecisionReason.GROUP_MEMBER)

    @Test
    fun aCodeMemberShowsTheCodeLineWithBlockAndKeep() {
        val line = ChatLinePolicy.lineFor(groupMember, emptyList(), listOf(member(AllowGroup.CODE)), on)
        assertThat(line).isEqualTo(ChatLine.CODE)
        assertThat(line!!.textRes).isEqualTo(R.string.pt_line_code)
        assertThat(line.actions).containsExactly(ChatLineAction.BLOCK, ChatLineAction.KEEP).inOrder()
    }

    @Test
    fun aWroteToMemberShowsTheWroteLine() {
        val line = ChatLinePolicy.lineFor(groupMember, emptyList(), listOf(member(AllowGroup.WROTE)), on)
        assertThat(line).isEqualTo(ChatLine.WROTE)
        assertThat(line!!.actions).containsExactly(ChatLineAction.BLOCK, ChatLineAction.KEEP).inOrder()
    }

    @Test
    fun keepMakesTheAllowedLineGoForGood() {
        assertThat(ChatLinePolicy.lineFor(groupMember, emptyList(), listOf(member(AllowGroup.CODE, true)), on)).isNull()
        // Members from the history check are born acknowledged: no line in old chats (answer 4).
        val history = member(AllowGroup.WROTE, source = MemberSource.HISTORY, acknowledged = true)
        assertThat(ChatLinePolicy.lineFor(groupMember, emptyList(), listOf(history), on)).isNull()
    }

    @Test
    fun aMembershipOfAGroupThatIsOffShowsNoLine() {
        val off = SpamSettings(codeGroupOn = false)
        assertThat(ChatLinePolicy.lineFor(groupMember, emptyList(), listOf(member(AllowGroup.CODE)), off)).isNull()
    }

    @Test
    fun anAllowedWordShowsTheWordLineUntilKeep() {
        val auto = rule(7, ListKind.ALLOW, RuleNotes.ALLOWED_FROM_KEYWORD)
        val verdict = SenderVerdict.Messages(DecisionReason.ALLOW_RULE, 7)
        assertThat(ChatLinePolicy.lineFor(verdict, listOf(auto), emptyList(), on)).isEqualTo(ChatLine.WORD)
        val kept = auto.copy(acknowledged = true)
        assertThat(ChatLinePolicy.lineFor(verdict, listOf(kept), emptyList(), on)).isNull()
    }

    @Test
    fun anAllowRuleTheUserMadeShowsNoLine() {
        val own = rule(7, ListKind.ALLOW, RuleNotes.ALLOWED_BY_YOU)
        val verdict = SenderVerdict.Messages(DecisionReason.ALLOW_RULE, 7)
        assertThat(ChatLinePolicy.lineFor(verdict, listOf(own), emptyList(), on)).isNull()
    }

    @Test
    fun aBlockedSenderShowsTheBlockedLineWithUnblock() {
        val block = rule(9, ListKind.BLOCK, RuleNotes.BLOCKED_BY_YOU)
        val verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, 9)
        val line = ChatLinePolicy.lineFor(verdict, listOf(block), emptyList(), on)
        assertThat(line).isEqualTo(ChatLine.BLOCKED)
        assertThat(line!!.actions).containsExactly(ChatLineAction.UNBLOCK)
    }

    @Test
    fun aSenderBlockedByABlockedWordShowsItsOwnLineWithUnblock() {
        // Step N item 3: a keyword hit that blocks a sender is visible in the chat, and Unblock takes it back.
        val word = rule(9, ListKind.BLOCK, RuleNotes.BLOCKED_FROM_KEYWORD)
        val verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, 9)
        val line = ChatLinePolicy.lineFor(verdict, listOf(word), emptyList(), on)
        assertThat(line).isEqualTo(ChatLine.BLOCKED_WORD)
        assertThat(line!!.textRes).isEqualTo(R.string.pt_line_blocked_word)
        assertThat(line.actions).containsExactly(ChatLineAction.UNBLOCK)
    }

    @Test
    fun androidsOwnBlockListShowsTheBlockedLine() {
        val verdict = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)
        assertThat(ChatLinePolicy.lineFor(verdict, emptyList(), emptyList(), on)).isEqualTo(ChatLine.BLOCKED)
    }

    @Test
    fun anUnknownSenderInSpamOffersNotSpamBlockAndAddContact() {
        val verdict = SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)
        val line = ChatLinePolicy.lineFor(verdict, emptyList(), emptyList(), on)
        assertThat(line).isEqualTo(ChatLine.UNKNOWN)
        assertThat(line!!.actions)
            .containsExactly(ChatLineAction.NOT_SPAM, ChatLineAction.BLOCK, ChatLineAction.ADD_CONTACT).inOrder()
    }

    @Test
    fun aContactOrAnUnsureVerdictShowsNoLine() {
        val contact = SenderVerdict.Messages(DecisionReason.CONTACT)
        assertThat(ChatLinePolicy.lineFor(contact, emptyList(), listOf(member(AllowGroup.CODE)), on)).isNull()
        assertThat(ChatLinePolicy.lineFor(null, emptyList(), emptyList(), on)).isNull()
    }

    @Test
    fun oneBannerHost() {
        val state = ChatLineState()
        state.update(ChatLine.UNKNOWN, filterOn = true)
        assertThat(state.hidesUnknownCard).isTrue()
        assertThat(state.unknownInSpam).isTrue()
        // In Messages with the filter off, the "Not in your contacts" card stays.
        state.update(null, filterOn = false)
        assertThat(state.hidesUnknownCard).isFalse()
        state.update(null, filterOn = true)
        assertThat(state.hidesUnknownCard).isTrue()
        state.clear()
        assertThat(state.hidesUnknownCard).isFalse()
    }

    @Test
    fun everyButtonHasItsApprovedLabel() {
        assertThat(actionLabel(ChatLineAction.KEEP)).isEqualTo(R.string.pt_line_keep)
        assertThat(actionLabel(ChatLineAction.NOT_SPAM)).isEqualTo(R.string.ux8c_not_spam)
        assertThat(actionLabel(ChatLineAction.UNBLOCK)).isEqualTo(R.string.blocknum_unblock)
        assertThat(actionLabel(ChatLineAction.BLOCK)).isEqualTo(R.string.i04_card_block)
        assertThat(actionLabel(ChatLineAction.ADD_CONTACT)).isEqualTo(R.string.i04_card_add_contact)
    }

    private companion object {
        const val SENDER = "+15550001234"
    }
}
