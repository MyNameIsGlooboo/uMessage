package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/** What the activity found out about the sender of the open conversation (I04). Compose reads it. */
class UnknownCardState {
    var visible by mutableStateOf(false)
        private set
    var caution by mutableStateOf(false)
        private set

    fun update(visible: Boolean, caution: Boolean) {
        this.visible = visible
        this.caution = caution
    }

    /** Remembers the dismissal; set by the activity, which knows the thread. */
    var onDismissed: () -> Unit = {}

    fun dismiss() {
        visible = false
        onDismissed()
    }
}

/**
 * In landscape with the keyboard up the window is only a few lines tall: the top bar, this card and the composer
 * together are taller than it, and the composer would be drawn over the card. The card steps aside until the
 * keyboard closes; portrait is unchanged.
 */
internal fun unknownCardCollapsed(landscape: Boolean, keyboardUp: Boolean): Boolean = landscape && keyboardUp

/** The card under the top bar of a conversation with a stranger; the buttons follow what the menu offers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnknownCardHost(vm: ThreadViewModel, actions: ThreadActions) {
    val state = vm.unknownCard
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (!state.visible || unknownCardCollapsed(landscape, WindowInsets.isImeVisible)) return
    val menu = vm.menu
    fun offer(available: Boolean, action: ThreadMenuAction): (() -> Unit)? =
        if (available) ({ actions.onMenuAction(action) }) else null
    UnknownSenderCard(
        caution = state.caution,
        onAddContact = offer(menu.addToContact, ThreadMenuAction.AddToContact),
        onAlwaysAllow = offer(menu.allowSender, ThreadMenuAction.AllowSender),
        onBlock = offer(menu.block, ThreadMenuAction.Block),
        onDismiss = state::dismiss,
    )
}

/** I04: "Not in your contacts", with the three things to do about it. A button whose action is null is left out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnknownSenderCard(
    caution: Boolean,
    onAddContact: (() -> Unit)?,
    onAlwaysAllow: (() -> Unit)?,
    onBlock: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.i04_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Symbols.Close,
                        contentDescription = stringResource(R.string.i04_card_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (caution) {
                Text(
                    stringResource(R.string.i04_card_caution),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
            // U01: the buttons take the container's own on-colour, which the theme keeps at 4.5:1 or more.
            val buttonColors = ButtonDefaults.textButtonColors(contentColor = colors.onSecondaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onAddContact?.let { CardButton(R.string.i04_card_add_contact, buttonColors, it) }
                onAlwaysAllow?.let { CardButton(R.string.i04_card_allow, buttonColors, it) }
                onBlock?.let { CardButton(R.string.i04_card_block, buttonColors, it) }
            }
        }
    }
}

@Composable
private fun CardButton(label: Int, colors: ButtonColors, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = colors) {
        Text(stringResource(label), fontWeight = FontWeight.SemiBold)
    }
}
