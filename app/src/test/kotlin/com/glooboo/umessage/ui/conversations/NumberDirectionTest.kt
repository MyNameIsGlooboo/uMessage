package com.glooboo.umessage.ui.conversations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.components.isolateNumbers
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MC-24: in the Persian (right-to-left) UI a number title must stay in a left-to-right isolate. */
@RunWith(AndroidJUnit4::class)
class NumberDirectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(title: String) = ConversationRow(
        threadId = 1, title = title, snippet = "سلام", dateLabel = "۹:۴۱",
        unread = false, unreadBadge = "", unreadCount = 0, pinned = false, muted = false, hasDraft = false,
        scheduled = false, simSlot = null, photoUri = "", isGroup = false, phoneNumber = "+447123456789",
    )

    @Test
    fun numberTitleIsIsolatedInARightToLeftList() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ConversationsTheme {
                    ConversationItem(
                        row = row("+44 712 345 6789"),
                        selected = false,
                        selecting = false,
                        callbacks = ConversationsCallbacks.None,
                        canArchive = true,
                    )
                }
            }
        }
        compose.onNodeWithText("⁦+44 712 345 6789⁩", useUnmergedTree = true).assertExists()
    }

    @Test
    fun onlyNumbersAreWrapped() {
        assertThat(isolateNumbers("Sara")).isEqualTo("Sara")
        assertThat(isolateNumbers("علی")).isEqualTo("علی")
        assertThat(isolateNumbers("+44 712 345 6789")).isEqualTo("⁦+44 712 345 6789⁩")
        assertThat(isolateNumbers("Ali, +447123456789")).isEqualTo("Ali, ⁦+447123456789⁩")
        assertThat(isolateNumbers("(021) 8888-1234")).isEqualTo("⁦(021) 8888-1234⁩")
        assertThat(isolateNumbers("۰۷۱۲ ۳۴۵ ۶۷۸۹")).isEqualTo("⁦۰۷۱۲ ۳۴۵ ۶۷۸۹⁩")
        // Short counts stay as they are, and text that is already isolated is not wrapped twice.
        assertThat(isolateNumbers("Mom 2")).isEqualTo("Mom 2")
        assertThat(isolateNumbers("⁦+44712⁩")).isEqualTo("⁦+44712⁩")
    }
}
