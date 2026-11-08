package com.glooboo.umessage.ui.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.SavedText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The saved text dialog: Delete only when editing, at the start edge, and it asks before deleting. */
@RunWith(AndroidJUnit4::class)
class SavedTextDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var deleted = 0
    private var saved: Pair<String, String>? = null
    private val existing = SavedText(id = "1", title = "Address", text = "1 Example Street")

    private fun show(initial: SavedText?, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    SavedTextDialog(
                        initial = initial,
                        onSave = { title, text -> saved = title to text },
                        onDelete = initial?.let { { deleted++ } },
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun deleteIsNotShownWhenCreatingANewSavedText() {
        show(null)
        compose.onNodeWithTag("dialog_delete").assertDoesNotExist()
        compose.onNodeWithTag("dialog_save").assertIsDisplayed()
    }

    @Test
    fun deleteSitsAtTheStartAndCancelAndSaveAtTheEnd() {
        show(existing)
        val delete = compose.onNodeWithTag("dialog_delete").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("dialog_cancel").getUnclippedBoundsInRoot()
        val save = compose.onNodeWithTag("dialog_save").getUnclippedBoundsInRoot()
        assertThat(delete.right.value).isLessThan(cancel.left.value)
        assertThat(cancel.right.value).isAtMost(save.left.value)
    }

    @Test
    fun inARightToLeftScreenTheOrderMirrors() {
        show(existing, LayoutDirection.Rtl)
        val delete = compose.onNodeWithTag("dialog_delete").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("dialog_cancel").getUnclippedBoundsInRoot()
        val save = compose.onNodeWithTag("dialog_save").getUnclippedBoundsInRoot()
        assertThat(delete.left.value).isGreaterThan(cancel.right.value)
        assertThat(save.right.value).isAtMost(cancel.left.value)
    }

    @Test
    fun deleteAsksFirstAndOnlyTheConfirmDeletes() {
        show(existing)
        compose.onNodeWithTag("dialog_delete").performClick()
        compose.onNodeWithText("Delete this saved text?").assertIsDisplayed()
        assertThat(deleted).isEqualTo(0)

        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.onNodeWithText("Delete this saved text?").assertDoesNotExist()
        assertThat(deleted).isEqualTo(0)

        compose.onNodeWithTag("dialog_delete").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun saveHandsBackTheTitleAndText() {
        show(existing)
        compose.onNodeWithTag("dialog_save").performClick()
        assertThat(saved).isEqualTo("Address" to "1 Example Street")
    }
}
