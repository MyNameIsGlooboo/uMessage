package com.glooboo.umessage.receivers

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * I06: the Undo of an old rule-only Block notice removes its rules again; a blank sender blocks nothing. The Block of
 * provider truth is in [BlockSenderReceiverMoveTest].
 */
@RunWith(AndroidJUnit4::class)
class BlockSenderReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val receiver = BlockSenderReceiver()

    private fun blockRules() = runBlocking { repository.rules.first().filter { it.kind == ListKind.BLOCK } }

    @Test
    fun anOldUndoRemovesTheRulesItCarries() = runBlocking {
        // An Undo notice of 0.4.14's rule-only Block names the rule ids it added.
        val id = repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+15550100"))

        val undo = Intent(BlockSenderReceiver.ACTION_UNDO)
            .putExtra(THREAD_ID, 42L)
            .putExtra(BlockSenderReceiver.EXTRA_RULE_IDS, longArrayOf(id))
        receiver.handle(context, repository, undo)

        assertThat(blockRules()).isEmpty()
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun aBlankAddressDoesNothing() = runBlocking {
        receiver.handle(context, repository, BlockSenderReceiver.blockIntent(context, " ", 42L))

        assertThat(blockRules()).isEmpty()
    }
}
