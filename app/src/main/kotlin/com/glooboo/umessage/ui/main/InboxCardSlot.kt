package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.ui.spam.ContactsOffBanner

/**
 * U03: the card due now ([InboxCardPolicy]). [answered] counts the cards answered or closed, so the next one still due
 * shows; [spamMoved] the chats the last first sort moved to Spam (provider truth, U13).
 */
@Composable
internal fun rememberInboxCard(answered: Int, spamMoved: Int = 0): InboxCard? {
    val context = LocalContext.current
    val w3c = remember(context) { W3cPrefs.get(context) }
    val cleanup = remember(context) { context.cleanupPrefs }
    return remember(answered, spamMoved) {
        InboxCardPolicy.pick(
            checklistDue = !w3c.checklistDismissed,
            cleanupDue = !cleanup.codeCardDismissed && cleanup.codeDays == 0,
            spamMovedDue = spamMoved > 0,
        )
    }
}

/** U03: the one card above the inbox's rows. Answering or closing it calls [onDone]; the host picks the next. */
@Composable
internal fun InboxCardSlot(
    card: InboxCard?,
    forwardRuleCount: Int,
    onOpenBlockedTab: () -> Unit,
    onOpenForwarding: () -> Unit,
    onDone: () -> Unit,
    spamMoved: Int = 0,
) {
    when (card) {
        InboxCard.SpamMoved -> SpamMovedCard(
            moved = spamMoved,
            onReview = {
                onDone()
                onOpenBlockedTab()
            },
        )
        InboxCard.Checklist -> FirstDayChecklist(
            forwardRuleCount = forwardRuleCount,
            onOpenBlockedTab = onOpenBlockedTab,
            onOpenForwarding = onOpenForwarding,
            onDone = onDone,
        )
        InboxCard.CodeCleanup -> CodeCleanupCard(onDone = onDone)
        null -> Unit
    }
}

/** The warnings above the list. While the set-up checklist shows, only the notifications one stays: no stacking. */
@Composable
internal fun InboxBanners(checklistShowing: Boolean) {
    NotificationsOffBanner()
    if (!checklistShowing) {
        ContactsOffBanner()
        BatteryBanner()
    }
}
