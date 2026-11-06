package com.glooboo.umessage.ui.conversations

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.glooboo.umessage.ui.theme.UMessageTheme

/** [UMessageTheme] for the conversation list previews. */
@Composable
fun ConversationsTheme(content: @Composable () -> Unit) {
    UMessageTheme(content = content)
}

internal fun Color.argb(): Int = toArgb()
