package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** I06: Block adds the sender rule and cancels the message notification; Undo removes the rule again. */
@RunWith(AndroidJUnit4::class)
class BlockSenderReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val receiver = BlockSenderReceiver()

    private fun blockRules() = runBlocking { repository.rules.first().filter { it.kind == ListKind.BLOCK } }

    @Test
    fun blockAddsARuleCancelsTheMessageAndOffersUndo() = runBlocking {
        manager.notify(42L.hashCode(), Notification.Builder(context, "c").setSmallIcon(1).build())

        receiver.handle(context, repository, BlockSenderReceiver.blockIntent(context, "+15550100", 42L))

        assertThat(blockRules().map { it.pattern }).containsExactly("+15550100")
        val posted = shadowOf(manager).allNotifications
        assertThat(posted.map { it.channelId }).containsExactly(BlockSenderReceiver.CHANNEL_ID)
        assertThat(posted.single().actions).hasLength(1)
    }

    @Test
    fun undoRemovesTheRule() = runBlocking {
        receiver.handle(context, repository, BlockSenderReceiver.blockIntent(context, "+15550100", 42L))
        val ruleIds = blockRules().map { it.id }.toLongArray()
        assertThat(ruleIds).hasLength(1)

        val undo = Intent(BlockSenderReceiver.ACTION_UNDO)
            .putExtra(THREAD_ID, 42L)
            .putExtra(BlockSenderReceiver.EXTRA_RULE_IDS, ruleIds)
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
