package com.glooboo.umessage.ui.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.spam.RuleNotes

/* Provider truth (4.3, U10; step N item 3): the one line about the sender of a one-to-one chat. */

/** What the chat line's buttons do. */
enum class ChatLineAction { BLOCK, KEEP, UNBLOCK, NOT_SPAM, ADD_CONTACT }

/** The six lines of 4.3, each with its text and its buttons in the order shown. */
enum class ChatLine(@StringRes val textRes: Int, val actions: List<ChatLineAction>) {
    /** A member of "Senders of a verification code", not acknowledged. */
    CODE(R.string.pt_line_code, listOf(ChatLineAction.BLOCK, ChatLineAction.KEEP)),

    /** A member of "People you wrote to", not acknowledged. */
    WROTE(R.string.pt_line_wrote, listOf(ChatLineAction.BLOCK, ChatLineAction.KEEP)),

    /** Allowed automatically by an allowed word (H4), not acknowledged. */
    WORD(R.string.pt_line_word, listOf(ChatLineAction.BLOCK, ChatLineAction.KEEP)),

    /** A block rule the user made (or Android's own block list). */
    BLOCKED(R.string.pt_line_blocked, listOf(ChatLineAction.UNBLOCK)),

    /** A block rule made by a blocked word (M-SP1, step N item 3): the visible line, Unblock takes it back. */
    BLOCKED_WORD(R.string.pt_line_blocked_word, listOf(ChatLineAction.UNBLOCK)),

    /** An unknown sender in Spam. */
    UNKNOWN(
        R.string.pt_line_unknown,
        listOf(ChatLineAction.NOT_SPAM, ChatLineAction.BLOCK, ChatLineAction.ADD_CONTACT),
    ),
}

/** Which line a chat shows (4.3). Pure, so every case is easy to test. */
object ChatLinePolicy {
    /**
     * The line for a one-to-one chat whose sender has [verdict] (null: not sure, no line). [rules] are every spam
     * rule, [members] the sender's own memberships (matched by the caller), [settings] the switches. Keep
     * (acknowledged) makes an "Allowed" line go for good; the membership or rule stays automatic, so a later block
     * still wins.
     */
    fun lineFor(
        verdict: SenderVerdict?,
        rules: List<SpamRule>,
        members: List<AllowGroupMember>,
        settings: SpamSettings,
    ): ChatLine? = when (verdict) {
        null -> null
        is SenderVerdict.Spam -> when {
            verdict.cause == SpamCause.UNKNOWN -> ChatLine.UNKNOWN
            madeByWord(rules, verdict.ruleId) -> ChatLine.BLOCKED_WORD
            else -> ChatLine.BLOCKED
        }
        is SenderVerdict.Messages -> allowedLine(verdict, rules, members, settings)
    }

    private fun madeByWord(rules: List<SpamRule>, ruleId: Long?): Boolean =
        rules.firstOrNull { it.id == ruleId }?.note?.let(RuleNotes::codeOf) == RuleNotes.BLOCKED_FROM_KEYWORD

    private fun allowedLine(
        verdict: SenderVerdict.Messages,
        rules: List<SpamRule>,
        members: List<AllowGroupMember>,
        settings: SpamSettings,
    ): ChatLine? = when (verdict.reason) {
        DecisionReason.ALLOW_RULE -> rules.firstOrNull { it.id == verdict.ruleId }
            ?.takeIf { RuleNotes.isMadeByFilter(it.note) && !it.acknowledged }
            ?.let { ChatLine.WORD }
        DecisionReason.GROUP_MEMBER -> {
            val open = members.filter { !it.acknowledged && settings.groupOn(it.group) }.map { it.group }
            when {
                AllowGroup.CODE in open -> ChatLine.CODE
                AllowGroup.WROTE in open -> ChatLine.WROTE
                else -> null
            }
        }
        else -> null
    }
}

/**
 * What the activity found out about the sender for the chat line (4.3). [line] null shows none. [hidesUnknownCard]:
 * one banner host, so in Spam the line is the only banner, and with the filter on the "Not in your contacts" card does
 * not show; with the filter off that card stays in Messages.
 */
class ChatLineState {
    var line by mutableStateOf<ChatLine?>(null)
        private set
    var hidesUnknownCard by mutableStateOf(false)
        private set

    /** The chat is in Spam as an unknown sender: a suggested reply asks first (D4 B2). */
    val unknownInSpam: Boolean get() = line == ChatLine.UNKNOWN

    /** Sends what the composer holds (set by the activity): a suggested reply confirmed in Spam goes at once. */
    var sendNow: () -> Unit = {}

    fun update(line: ChatLine?, filterOn: Boolean) {
        this.line = line
        hidesUnknownCard = line != null || filterOn
    }

    fun clear() {
        line = null
        hidesUnknownCard = false
    }
}

/** Test tag of the chat line. */
internal const val CHAT_LINE_TAG = "chat_line"

/** The line under the top bar with its buttons (4.3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatLineCard(line: ChatLine, onAction: (ChatLineAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(CHAT_LINE_TAG),
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 2.dp)) {
            Text(
                stringResource(line.textRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            val buttonColors = ButtonDefaults.textButtonColors(contentColor = colors.onSecondaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                line.actions.forEach { action ->
                    TextButton(onClick = { onAction(action) }, colors = buttonColors) {
                        Text(stringResource(actionLabel(action)), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@StringRes
internal fun actionLabel(action: ChatLineAction): Int = when (action) {
    ChatLineAction.BLOCK -> R.string.i04_card_block
    ChatLineAction.KEEP -> R.string.pt_line_keep
    ChatLineAction.UNBLOCK -> R.string.blocknum_unblock
    ChatLineAction.NOT_SPAM -> R.string.ux8c_not_spam
    ChatLineAction.ADD_CONTACT -> R.string.i04_card_add_contact
}
