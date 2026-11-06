package com.glooboo.umessage.ui.conversations

import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview

private val sampleRows = listOf(
    ConversationRow(
        threadId = 1, title = "Maya Rivera", snippet = "See you at 6, bring the keys", dateLabel = "09:41",
        unread = true, unreadBadge = "3", unreadCount = 3, pinned = true, muted = false, hasDraft = false,
        scheduled = false, simSlot = 2, photoUri = "", isGroup = false, phoneNumber = "+15551230001",
    ),
    ConversationRow(
        threadId = 2, title = "Weekend crew", snippet = "Anyone up for the lake?", dateLabel = "Yesterday",
        unread = false, unreadBadge = "", unreadCount = 0, pinned = true, muted = true, hasDraft = false,
        scheduled = false, simSlot = null, photoUri = "", isGroup = true, phoneNumber = "a,b,c",
    ),
    ConversationRow(
        threadId = 3, title = "Bank of Example", snippet = "Your code is 482913", dateLabel = "Mon",
        unread = true, unreadBadge = "99+", unreadCount = 140, pinned = false, muted = false, hasDraft = false,
        scheduled = false, simSlot = null, photoUri = "", isGroup = false, phoneNumber = "BANKEX",
    ),
    ConversationRow(
        threadId = 4, title = "+1 555 123 0004", snippet = "Running late, start without me", dateLabel = "Oct 12",
        unread = false, unreadBadge = "", unreadCount = 0, pinned = false, muted = false, hasDraft = true,
        scheduled = false, simSlot = 1, photoUri = "", isGroup = false, phoneNumber = "+15551230004",
    ),
)

private val sampleState = ConversationsUiState(loading = false, rows = sampleRows)

@Composable
private fun Host(state: ConversationsUiState) {
    ConversationsTheme {
        ConversationsScreen(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            callbacks = ConversationsCallbacks.None,
        )
    }
}

@Preview(name = "List light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ListLightPreview() = Host(sampleState)

@Preview(
    name = "List dark", showBackground = true, widthDp = 360, heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ListDarkPreview() = Host(sampleState)

@Preview(name = "Selecting", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun SelectingPreview() = Host(
    sampleState.copy(
        selected = setOf(1L, 3L),
        selection = SelectionActions(count = 2, canPin = true, canMarkRead = true, canArchive = true),
    ),
)

@Preview(name = "Empty", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyPreview() = Host(ConversationsUiState(loading = false))

@Preview(
    name = "Empty dark with blocked", showBackground = true, widthDp = 360, heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyDarkPreview() = Host(ConversationsUiState(loading = false))

@Preview(name = "Loading", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun LoadingPreview() = Host(ConversationsUiState(loading = false, importing = true))

@Preview(name = "Large text", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun LargeTextPreview() = Host(sampleState)

@Preview(name = "List RTL", showBackground = true, widthDp = 360, heightDp = 640, locale = "fa")
@Composable
private fun ListRtlPreview() = Host(sampleState)

@Preview(name = "Selection bar light", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
private fun SelectionBarPreview() = ConversationsTheme {
    ConversationsSelectionBar(
        selection = SelectionActions(count = 2, canPin = true, canArchive = true, canMarkRead = true),
        callbacks = ConversationsCallbacks.None,
    )
}

@Preview(
    name = "Selection bar dark", showBackground = true, widthDp = 360, heightDp = 96,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SelectionBarDarkPreview() = ConversationsTheme {
    ConversationsSelectionBar(
        selection = SelectionActions(count = 1, canUnpin = true),
        callbacks = ConversationsCallbacks.None,
    )
}

@Preview(name = "Selection bar RTL", showBackground = true, widthDp = 360, heightDp = 96, locale = "fa")
@Composable
private fun SelectionBarRtlPreview() = ConversationsTheme {
    ConversationsSelectionBar(
        selection = SelectionActions(count = 3, canPin = true, canArchive = true),
        callbacks = ConversationsCallbacks.None,
    )
}
