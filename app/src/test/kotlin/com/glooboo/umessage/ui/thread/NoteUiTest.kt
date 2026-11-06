package com.glooboo.umessage.ui.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.MessageNotes
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The note box under a bubble and the edit dialog, rendered under Robolectric. */
@RunWith(AndroidJUnit4::class)
class NoteUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: String? = null
    private var deleted = 0
    private var dismissed = 0

    private fun showDialog(initial: String = "", hasNote: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                NoteEditDialog(
                    initial = initial,
                    hasNote = hasNote,
                    onSave = { saved = it },
                    onDelete = { deleted++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun theNoteBoxShowsTheTextAndSpeaksItAsANote() {
        compose.setContent { MaterialTheme { NoteBox("Rent for May", 240.dp, onClick = {}) } }

        compose.onNodeWithContentDescription("Note: Rent for May").assertIsDisplayed()
    }

    @Test
    fun tappingTheNoteBoxOpensTheEditor() {
        var taps = 0
        compose.setContent { MaterialTheme { NoteBox("Rent for May", 240.dp, onClick = { taps++ }) } }

        compose.onNodeWithContentDescription("Note: Rent for May").performClick()

        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun aPersianNoteInARightToLeftScreenIsShown() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { NoteBox("اجاره خانه", 240.dp, onClick = {}) }
            }
        }

        compose.onNodeWithContentDescription("Note: اجاره خانه").assertIsDisplayed()
    }

    @Test
    fun aLongNoteStillRendersInANarrowBox() {
        val long = (1..40).joinToString(" ") { "word$it" }
        compose.setContent { MaterialTheme { NoteBox(long, 120.dp, onClick = {}) } }

        // The text itself is cut after NOTE_MAX_LINES lines; the description still holds all of it.
        compose.onNodeWithContentDescription("Note: $long").assertIsDisplayed()
    }

    @Test
    fun savingGivesBackTheTypedText() {
        showDialog()

        compose.onNode(hasSetTextAction()).performTextReplacement("Electricity bill")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("Electricity bill")
    }

    @Test
    fun savingBlankTextIsHandedOnForTheStoreToDelete() {
        showDialog(initial = "old", hasNote = true)

        compose.onNode(hasSetTextAction()).performTextReplacement("   ")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("   ")
    }

    @Test
    fun cancelDismissesWithoutSaving() {
        showDialog(initial = "old", hasNote = true)

        compose.onNodeWithText("Cancel").performClick()

        assertThat(dismissed).isEqualTo(1)
        assertThat(saved).isNull()
    }

    @Test
    fun deleteIsOfferedOnlyWhenTheMessageHasANote() {
        showDialog(initial = "old", hasNote = true)
        compose.onNodeWithText("Edit note").assertIsDisplayed()
        compose.onNodeWithText("Delete note").performClick()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun aNewNoteHasNoDeleteButton() {
        showDialog()

        compose.onNodeWithText("Add note").assertIsDisplayed()
        compose.onAllNodesWithText("Delete note").assertCountEquals(0)
    }

    @Test
    fun theCounterFollowsTheTextAndStopsAtFiveHundred() {
        showDialog(initial = "abc")
        compose.onNodeWithText("3/500").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(MessageNotes.MAX_LENGTH + 100))
        compose.onNodeWithText("500/500").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).hasLength(MessageNotes.MAX_LENGTH)
    }

    @Test
    fun cappedNoteCutsOnlyWhenTooLong() {
        val fits = TextFieldValue("a".repeat(MessageNotes.MAX_LENGTH), TextRange(3))
        assertThat(cappedNote(fits)).isEqualTo(fits)

        val capped = cappedNote(TextFieldValue("b".repeat(MessageNotes.MAX_LENGTH + 1)))
        assertThat(capped.text).hasLength(MessageNotes.MAX_LENGTH)
        assertThat(capped.selection).isEqualTo(TextRange(MessageNotes.MAX_LENGTH))
    }
}
