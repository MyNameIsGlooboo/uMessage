package com.glooboo.umessage.ui.starred

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.StarredItem
import com.glooboo.umessage.helpers.StarredKind
import com.glooboo.umessage.helpers.StarredPage
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Starred screen shows one row per starred message and hands taps to its actions. */
@RunWith(AndroidJUnit4::class)
class StarredScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(
        id: Long,
        text: String,
        title: String = "Sam",
        kind: StarredKind = StarredKind.TEXT,
        note: Boolean = false,
        gone: Boolean = false,
    ) = StarredItem(
        id = id, isMMS = false, threadId = 7, title = title, photoUri = "", text = text, kind = kind,
        dateMillis = 1_700_000_000_000L + id, hasNote = note, chatGone = gone, isGroup = false,
    )

    private class Taps {
        val opened = mutableListOf<StarredItem>()
        val toggled = mutableListOf<StarredItem>()
        val unstarred = mutableListOf<Set<MessageKey>>()

        fun actions() = StarredActions(
            onBack = {}, onOpen = { opened += it }, onToggle = { toggled += it }, onSelectAll = {},
            onClearSelection = {}, onUnstar = { unstarred += it }, onShowMore = {},
        )
    }

    private fun show(page: StarredPage?, taps: Taps, selected: Set<MessageKey> = emptySet()) {
        compose.setContent {
            UMessageTheme { StarredScreen(page = page, selected = selected, actions = taps.actions()) }
        }
    }

    @Test
    fun twoStarsFromOneSenderShowAsTwoRows() {
        show(StarredPage(listOf(item(1, "first note"), item(2, "second note"), item(3, "other", "Kim"))), Taps())

        compose.onNodeWithText("first note").assertIsDisplayed()
        compose.onNodeWithText("second note").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Starred").assertCountEquals(3)
        compose.onAllNodesWithText("Sam").assertCountEquals(2)
    }

    @Test
    fun aPictureMessageSaysSoAndANoteGetsItsMark() {
        show(StarredPage(listOf(item(1, "", kind = StarredKind.PICTURE), item(2, "with note", note = true))), Taps())

        compose.onNodeWithText("Picture").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Has notes").assertCountEquals(1)
    }

    @Test
    fun aTapOpensThatMessage() {
        val taps = Taps()
        show(StarredPage(listOf(item(1, "first note"), item(2, "second note"))), taps)

        compose.onNodeWithText("second note").performClick()

        assertThat(taps.opened.map { it.id }).containsExactly(2L)
    }

    @Test
    fun aLongPressStartsSelection() {
        val taps = Taps()
        show(StarredPage(listOf(item(1, "first note"))), taps)

        compose.onNodeWithText("first note").performTouchInput { longClick() }

        assertThat(taps.toggled.map { it.id }).containsExactly(1L)
        assertThat(taps.opened).isEmpty()
    }

    @Test
    fun theSelectionBarUnstarsTheSelectedRows() {
        val taps = Taps()
        val selected = setOf(MessageKey(2, false))
        show(StarredPage(listOf(item(1, "first note"), item(2, "second note"))), taps, selected)

        compose.onNode(hasContentDescription("Unstar")).performClick()

        assertThat(taps.unstarred).containsExactly(selected)
    }

    @Test
    fun aTapWhileSelectingTogglesInsteadOfOpening() {
        val taps = Taps()
        show(StarredPage(listOf(item(1, "first note"))), taps, setOf(MessageKey(9, false)))

        compose.onNodeWithText("first note").performClick()

        assertThat(taps.toggled.map { it.id }).containsExactly(1L)
        assertThat(taps.opened).isEmpty()
    }

    @Test
    fun noStarsShowsTheEmptyText() {
        show(StarredPage(), Taps())

        compose.onNodeWithText("No starred messages").assertIsDisplayed()
    }

    @Test
    fun aDeletedChatsRowShowsItsSavedTextInsteadOfOpening() {
        val taps = Taps()
        show(StarredPage(listOf(item(1, "saved text", gone = true))), taps)

        compose.onNodeWithText("saved text").performClick()

        assertThat(taps.opened).isEmpty()
        compose.onNodeWithText("This chat was deleted. Only the saved text is left.").assertIsDisplayed()
    }

    @Test
    fun searchNarrowsTheRows() {
        show(StarredPage(listOf(item(1, "pay rent"), item(2, "see you"))), Taps())

        compose.onNodeWithContentDescription("Search starred messages").performClick()
        compose.onNodeWithText("Search starred messages").performTextInput("rent")

        compose.onNodeWithText("pay rent").assertIsDisplayed()
        compose.onAllNodesWithText("see you").assertCountEquals(0)
    }

    @Test
    fun tapIntentCarriesTheThreadAndTheMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = starredOpenIntent(context, item(42, "x", title = "Sam").copy(isMMS = true))

        assertThat(intent.component?.className).isEqualTo(ThreadActivity::class.java.name)
        assertThat(intent.getLongExtra(THREAD_ID, -1)).isEqualTo(7L)
        assertThat(intent.getStringExtra(THREAD_TITLE)).isEqualTo("Sam")
        assertThat(intent.getLongExtra(SEARCHED_MESSAGE_ID, -1)).isEqualTo(42L)
        assertThat(intent.getBooleanExtra(SEARCHED_MESSAGE_IS_MMS, false)).isTrue()
    }
}
