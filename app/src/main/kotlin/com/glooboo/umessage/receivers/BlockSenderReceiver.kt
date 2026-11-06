package com.glooboo.umessage.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.theme.currentColorScheme

/**
 * I06: the notification "Block" button and the "Undo" of the notice it leaves. Block adds the same uMessage rule as
 * "Block number" in the thread menu ([SenderBlocking]), so new texts from the sender go to Blocked messages and the
 * rule can be removed there or here.
 */
class BlockSenderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    val repository = runCatching { UMessage.repository(context) }.getOrNull()
                    if (repository != null) runBlocking { handle(context, repository, intent) }
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    internal suspend fun handle(context: Context, repository: SpamRepository, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        when (intent.action) {
            ACTION_BLOCK -> {
                if (address.isBlank()) return
                val change = runCatching { SenderBlocking.block(repository, listOf(address)) }.getOrNull() ?: return
                context.notificationManager.cancel(threadId.hashCode())
                showBlocked(context, address, threadId, change.addedRuleIds)
            }

            ACTION_UNDO -> {
                val ids = intent.getLongArrayExtra(EXTRA_RULE_IDS)?.toList().orEmpty()
                runCatching { SenderBlocking.undo(repository, SenderBlocking.Change(ids, emptyList())) }
                    .onFailure { return }
                context.notificationManager.cancel(blockedNotificationId(threadId))
            }
        }
    }

    /** A quiet "Blocked <number>" notice with Undo. It stays for a while, so a wrong tap can be taken back. */
    private fun showBlocked(context: Context, address: String, threadId: Long, ruleIds: List<Long>) {
        val manager = context.notificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notif_block_channel), NotificationManager.IMPORTANCE_LOW
            )
        )
        val id = blockedNotificationId(threadId)
        val undo = Intent(context, BlockSenderReceiver::class.java).apply {
            action = ACTION_UNDO
            putExtra(THREAD_ID, threadId)
            putExtra(EXTRA_RULE_IDS, ruleIds.toLongArray())
        }
        val undoPending = PendingIntent.getBroadcast(
            context, id, undo, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setContentTitle(context.getString(R.string.notif_block_done_title, displayNumber(context, address)))
            .setContentText(context.getString(R.string.notif_block_done_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(UNDO_VISIBLE_MS)
            .addAction(
                NotificationCompat.Action.Builder(0, context.getString(R.string.notif_block_undo), undoPending)
                    .setAuthenticationRequired(true)
                    .build()
            )
            .build()
        manager.notify(id, notification)
    }

    companion object {
        const val ACTION_BLOCK = "com.glooboo.spamless.action.BLOCK_SENDER"
        const val ACTION_UNDO = "com.glooboo.spamless.action.UNDO_BLOCK_SENDER"
        const val EXTRA_ADDRESS = "com.glooboo.spamless.extra.BLOCK_ADDRESS"
        const val EXTRA_RULE_IDS = "com.glooboo.spamless.extra.BLOCK_RULE_IDS"
        const val CHANNEL_ID = "sender_blocked_undo"

        /** How long the Undo notice stays (ms). */
        const val UNDO_VISIBLE_MS = 10L * 60 * 1000

        private const val NOTICE_SALT = 0x0B10C

        /** Separate from the message notification (the thread id's hash) so cancelling one keeps the other. */
        fun blockedNotificationId(threadId: Long): Int = threadId.hashCode() xor NOTICE_SALT

        /** The intent of the notification's Block button. */
        fun blockIntent(context: Context, address: String, threadId: Long): Intent =
            Intent(context, BlockSenderReceiver::class.java).apply {
                action = ACTION_BLOCK
                putExtra(EXTRA_ADDRESS, address)
                putExtra(THREAD_ID, threadId)
            }
    }
}
