package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.ui.components.ConfirmDialog

/**
 * The question asked once after a "Not spam" that moved texts of senders going by a name ("BANK-X"). No allow rule
 * is made for such a name on its own, because anyone can send texts under a name. [byToken] lists the names each
 * restore that asked brought in, so the Undo of one restore takes only its names back. The question is kept in
 * memory only: after the process dies it is gone, and a blocked chat then does not open the chat (rare, accepted).
 */
data class NameAllowPrompt(val byToken: Map<Long, List<String>>) {
    val tokens: Set<Long> get() = byToken.keys

    /** One entry per name, whatever its case. */
    val names: List<String> get() = byToken.values.flatten().distinctBy { it.lowercase() }
}

/** Reads sender names the way the filter does, so "HDFCBK" is taken to allow "VM-HDFCBK". The region is not used. */
private val senderMatcher = RuleMatcher(NumberNormalizer { "US" })

/** Whether an allow rule for both SIMs already lets [name] through, by the filter's own rule. */
internal fun isNameAllowed(name: String, rules: List<SpamRule>): Boolean {
    val message = IncomingMessage(address = name, body = "", subscriptionId = 0)
    return rules.any {
        it.kind == ListKind.ALLOW && it.type == RuleType.SENDER_ID && it.subscriptionId == null &&
            senderMatcher.matches(it, message)
    }
}

/** How many names the question lists before it says "and N more". */
private const val MAX_NAMES_SHOWN = 3

/**
 * The names worth asking about: senders that go by a name, of messages that really reached the inbox ([movedFrom],
 * one address per moved message), and that no allow rule for both SIMs names yet (see [isNameAllowed]).
 * One entry per name, whatever its case.
 */
internal fun namesToOffer(movedFrom: Collection<String>, rules: List<SpamRule>): List<String> {
    return movedFrom.map { it.trim() }
        .filter { it.isNotEmpty() && SenderBlocking.isSpoofable(it) }
        .distinctBy { it.lowercase() }
        .filterNot { isNameAllowed(it, rules) }
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
        confirmLabel = stringResource(R.string.nameallow_allow),
        onConfirm = onAllow,
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.nameallow_title),
        dismissLabel = stringResource(R.string.nameallow_not_now),
    )
}
