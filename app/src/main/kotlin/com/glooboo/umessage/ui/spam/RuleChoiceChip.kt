package com.glooboo.umessage.ui.spam

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glooboo.umessage.ui.components.UMessageFilterChip

/**
 * A choice chip for the rule editors. The selected one also shows a check mark, so the choice does not rest on a faint
 * fill alone (VS-09, BR-42). It is the shared [UMessageFilterChip]: secondaryContainer fill and a 48dp touch target.
 */
@Composable
fun RuleChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    UMessageFilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier)
}
