package com.glooboo.umessage.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.ui.components.TEXT_CONTRAST
import com.glooboo.umessage.ui.components.contrastRatio

/** The inbox list is a card that curves at the top, like Google Messages; the square bottom runs under the bar. */
private val InboxCardCorner = 28.dp
private val InboxCardShape = RoundedCornerShape(topStart = InboxCardCorner, topEnd = InboxCardCorner)

/**
 * The tone behind the top bar and the banners above the card: the scheme's surfaceContainer, which sits a step off the
 * card. The Wallpaper schemes at raised contrast can make that container too close to [onSurface]; the page background
 * is used then, so the bar's text and icons stay readable and only the curve is lost.
 */
internal fun inboxTopColor(container: Color, background: Color, onSurface: Color): Color =
    if (contrastRatio(container, onSurface) >= TEXT_CONTRAST) container else background

@Composable
internal fun rememberInboxTopColor(): Color {
    val colors = MaterialTheme.colorScheme
    return inboxTopColor(colors.surfaceContainer, colors.background, colors.onSurface)
}

/** The list area: clipped to [InboxCardShape] and filled with the page background, which the list itself draws too. */
@Composable
internal fun Modifier.inboxCard(): Modifier =
    clip(InboxCardShape).background(MaterialTheme.colorScheme.background)
