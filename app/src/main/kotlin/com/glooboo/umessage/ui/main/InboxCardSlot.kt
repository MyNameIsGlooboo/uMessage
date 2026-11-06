package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.helpers.cleanupPrefs

/**
 * U03: the one card above the inbox's rows ([InboxCardPolicy]). Answering or closing a card shows the next one that is
 * still due, if any.
 */
@Composable
internal fun InboxCardSlot(
    newBlocked: Int,
    forwardRuleCount: Int,
    onOpenBlockedTab: () -> Unit,
    onOpenForwarding: () -> Unit,
) {
    val context = LocalContext.current
    val w3c = remember(context) { W3cPrefs.get(context) }
    val cleanup = remember(context) { context.cleanupPrefs }
    var answered by remember { mutableIntStateOf(0) }
    val card = remember(answered, newBlocked) {
        InboxCardPolicy.pick(
            spamHeldDue = !w3c.firstBlockReceiptShown && newBlocked > 0,
            checklistDue = !w3c.checklistDismissed,
            cleanupDue = !cleanup.codeCardDismissed && cleanup.codeDays == 0,
        )
    }
    val next: () -> Unit = { answered++ }
    when (card) {
        InboxCard.SpamHeld -> FirstBlockReceipt(newBlocked = newBlocked, onReview = onOpenBlockedTab, onDone = next)
        InboxCard.Checklist -> FirstDayChecklist(
            forwardRuleCount = forwardRuleCount,
            onOpenBlockedTab = onOpenBlockedTab,
            onOpenForwarding = onOpenForwarding,
            onDone = next,
        )
        InboxCard.CodeCleanup -> CodeCleanupCard(onDone = next)
        null -> Unit
    }
}
