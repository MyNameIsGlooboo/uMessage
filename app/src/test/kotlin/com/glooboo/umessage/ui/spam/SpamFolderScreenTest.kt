package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.conversations.ConversationFolder
import com.glooboo.umessage.ui.conversations.ConversationFolderScreen
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.SpamFolderActions
import com.glooboo.umessage.ui.conversations.SwipeAction
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.main.SpamMovedCard
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.thread.CHAT_LINE_TAG
import com.glooboo.umessage.ui.thread.ChatLine
import com.glooboo.umessage.ui.thread.ChatLineAction
import com.glooboo.umessage.ui.thread.ChatLineCard
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Provider truth, step C (4.2, 4.3, U13): the Spam tab on the shared folder screen, the chat line and the card, as the
 * user sees them. Names and numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class SpamFolderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun row(id: Long, title: String, cause: SpamCause, code: String? = null, unread: Boolean = false) =
        ConversationRow(
            threadId = id, title = title, snippet = "Offer $id", dateLabel = "10:00", unread = unread,
            unreadBadge = "", unreadCount = 0, pinned = false, muted = false, hasDraft = false, scheduled = false,
            simSlot = null, photoUri = "", isGroup = false, phoneNumber = title, otpCode = code,
            causeLine = app.getString(SpamChatsPolicy.causeRes(cause)),
        )

    private val causes = mapOf(1L to SpamCause.UNKNOWN, 2L to SpamCause.BLOCKED)
    private val rows = listOf(
        row(1, "+15550001260", SpamCause.UNKNOWN, code = "4821", unread = true),
        row(2, "+15550001261", SpamCause.BLOCKED),
    )

    private val calls = mutableListOf<String>()
    private val snackbar = SnackbarHostState()
    private var deleteAllowed = true

    private fun show(rows: List<ConversationRow> = this.rows, intro: Boolean = false) {
        val actions = SpamFolderActions(
            selection = { ids -> SpamChatsPolicy.selection(ids.mapNotNull { causes[it] }, anyUnread = true) },
            onNotSpam = { calls += "notSpam$it" },
            onUnblock = { calls += "unblock$it" },
            onBlock = { calls += "block$it" },
            onMarkRead = { calls += "read$it" },
            deleteOffReason = { if (deleteAllowed) null else R.string.pt_delete_not_default },
            swipeFor = { SwipeAction(Symbols.Inbox, "x") { } },
        )
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                ConversationFolderScreen(
                    folder = ConversationFolder.Spam,
                    rows = rows,
                    loading = false,
                    onBack = null,
                    onOpen = {},
                    onRestore = {},
                    onDelete = { calls += "delete$it" },
                    onEmpty = {},
                    onMarkAllRead = { calls += "markAll" },
                    snackbarHostState = snackbar,
                    intro = if (intro) {
                        {
                            SpamStrips(
                                filterOn = false, olderSpam = 3, onTurnOn = { calls += "turnOn" }, onOpenOlder = {},
                            )
                        }
                    } else {
                        null
                    },
                    spam = actions,
                )
            }
        }
    }

    private fun select(title: String) =
        compose.onNodeWithText(title, substring = true, useUnmergedTree = true).performTouchInput { longClick() }

    private fun string(res: Int) = app.getString(res)

    @Test
    fun eachRowShowsItsCauseLineAndACodeCanBeCopied() {
        show()
        compose.onNodeWithText(string(R.string.spam_reason_unknown_sender)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.e_note_blocked_by_you)).assertIsDisplayed()
        compose.onNodeWithText("4821", substring = true).assertIsDisplayed()
    }

    @Test
    fun anUnknownSenderOffersNotSpamAndBlock() {
        show()
        select("0001260")
        compose.onNodeWithContentDescription(string(R.string.ux8c_not_spam)).performClick()
        assertThat(calls).containsExactly("notSpam[1]")
    }

    @Test
    fun aBlockedSenderOffersUnblock() {
        show()
        select("0001261")
        compose.onNodeWithContentDescription(string(R.string.blocknum_unblock)).performClick()
        assertThat(calls).containsExactly("unblock[2]")
    }

    @Test
    fun aMixedChoiceOffersOnlyDeleteAndMarkAsRead() {
        show()
        select("0001260")
        compose.onNodeWithText("+15550001261", substring = true, useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription(string(R.string.ux8c_not_spam)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.blocknum_unblock)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.i04_card_block)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.convlist_mark_read)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.screens_delete)).assertIsDisplayed()
    }

    @Test
    fun deleteAsksWithTheClearConfirm() {
        show()
        select("0001260")
        compose.onNodeWithContentDescription(string(R.string.screens_delete)).performClick()
        val title = app.resources.getQuantityString(R.plurals.pt_spam_delete_title, 1, 1)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_spam_delete_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.spam_action_delete)).performClick()
        assertThat(calls).containsExactly("delete[1]")
    }

    @Test
    fun deleteIsMarkedOffAndSaysWhyWhileNotTheDefaultApp() {
        deleteAllowed = false
        val offReason = string(R.string.pt_delete_not_default)
        show()
        select("0001260")
        // C-18: the button says why it is off, as its state.
        compose.onNodeWithContentDescription(string(R.string.screens_delete))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, offReason))
            .performClick()
        compose.onNodeWithText(string(R.string.pt_spam_delete_body)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.pt_delete_not_default)).assertIsDisplayed()
        assertThat(calls).isEmpty()
    }

    @Test
    fun theMenuHasMarkAllReadAndNoEmpty() {
        show()
        compose.onNodeWithContentDescription(string(R.string.nav_folder_more)).performClick()
        compose.onNodeWithText(string(R.string.nav_folder_mark_all_read)).performClick()
        assertThat(calls).containsExactly("markAll")
    }

    @Test
    fun withTheFilterOffTheStripOffersTurnOnAlsoOnAnEmptyList() {
        show(rows = emptyList(), intro = true)
        compose.onNodeWithText(string(R.string.pt_spam_filter_off_strip)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_spam_filter_off_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_older_spam_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_spam_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.cleanup_bin_confirm)).performClick()
        assertThat(calls).containsExactly("turnOn")
    }

    @Test
    fun theChatLineShowsItsTextAndButtons() {
        val tapped = mutableListOf<ChatLineAction>()
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                ChatLineCard(ChatLine.CODE, onAction = { tapped += it })
            }
        }
        compose.onNodeWithTag(CHAT_LINE_TAG).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_line_code)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_line_keep)).performClick()
        compose.onNodeWithText(string(R.string.i04_card_block)).performClick()
        assertThat(tapped).containsExactly(ChatLineAction.KEEP, ChatLineAction.BLOCK).inOrder()
    }

    @Test
    fun theMovedCardNamesTheCountAndReviewOpensSpam() {
        var reviewed = 0
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                SpamMovedCard(moved = 4, onReview = { reviewed++ })
            }
        }
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.pt_card_moved, 4, 4)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pt_card_moved_body)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.w3c_receipt_review)).performClick()
        assertThat(reviewed).isEqualTo(1)
    }
}
