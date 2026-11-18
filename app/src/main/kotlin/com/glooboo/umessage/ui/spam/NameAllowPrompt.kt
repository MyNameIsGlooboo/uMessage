package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.ConfirmDialog

/**
 * The question asked once after a "Not spam" that moved texts of senders going by a name ("BANK-X"). No allow rule
 * is made for such a name on its own, because anyone can send texts under a name. [token] is the restore that asked,
 * so its Undo takes the question back.
 */
data class NameAllowPrompt(val token: Long, val names: List<String>)

/** How many names the question lists before it says "and N more". */
private const val MAX_NAMES_SHOWN = 3

/**
 * The names worth asking about: senders that go by a name, of messages that really reached the inbox ([movedFrom],
 * one address per moved message), and that no allow rule names yet. One entry per name, whatever its case.
 */
internal fun namesToOffer(movedFrom: Collection<String>, rules: List<SpamRule>): List<String> {
    val allowed = rules.filter { it.kind == ListKind.ALLOW && it.type == RuleType.SENDER_ID }
        .map { it.pattern.trim().lowercase() }
        .toSet()
    return movedFrom.map { it.trim() }
        .filter { it.isNotEmpty() && SenderBlocking.isSpoofable(it) }
        .distinctBy { it.lowercase() }
        .filter { it.lowercase() !in allowed }
}

/** The question's text: one name, or up to three and "and N more". */
internal fun nameAllowBody(resources: Resources, names: List<String>): String {
    val shown = names.take(MAX_NAMES_SHOWN).joinToString(", ")
    if (names.size == 1) return resources.getString(R.string.nameallow_body, shown)
    val list = if (names.size > MAX_NAMES_SHOWN) {
        resources.getString(R.string.nameallow_more, shown, names.size - MAX_NAMES_SHOWN)
    } else {
        shown
    }
    return resources.getString(R.string.nameallow_body_many, list)
}

/** "Always allow this name?" with the two answers. Dismissing the dialog is the same as "Not now". */
@Composable
internal fun NameAllowDialog(
    names: List<String>,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmDialog(
        message = nameAllowBody(LocalResources.current, names),
        confirmLabel = stringResource(R.string.decm_always_allow),
        onConfirm = onAllow,
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.nameallow_title),
        dismissLabel = stringResource(R.string.setup_banner_dismiss),
    )
}
