package com.glooboo.umessage.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.conversations.StoreUnread
import com.glooboo.umessage.ui.conversations.UnreadTexts
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SenderMoves
import org.json.JSONArray
import org.json.JSONObject
import com.glooboo.umessage.ui.theme.currentColorScheme

/**
 * I06: the notification "Block" button and the "Undo" of the notice it leaves. Block adds the same uMessage rule as
 * "Block number" in the thread menu, so the sender's chat goes to Spam; Undo here takes it back.
 *
 * Provider truth (4.1, U5, M-SP3): Block also removes the sender's automatic allow rules and group memberships, marks
 * the chat read and sorts it again, so it moves to Spam. The Undo intent carries the whole receipt ([BlockUndoCodec]),
 * so Undo puts back the rules, the memberships and the unread state, also after the process died.
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
            ACTION_BLOCK -> when {
                address.isBlank() -> Unit
                else -> moveToSpam(context, repository, address, threadId)
            }

            ACTION_UNDO -> {
                val move = intent.getStringExtra(EXTRA_UNDO)?.let(BlockUndoCodec::decode)
                if (move != null) {
                    undoMove(context, repository, move, threadId)
                } else {
                    undoRules(context, repository, intent)
                }
            }
        }
    }

    /** 0.4.14: the Undo removes the rule ids it carries. */
    private suspend fun undoRules(context: Context, repository: SpamRepository, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val ids = intent.getLongArrayExtra(EXTRA_RULE_IDS)?.toList().orEmpty()
        runCatching { SenderBlocking.undo(repository, SenderBlocking.Change(ids, emptyList())) }.onFailure { return }
        context.notificationManager.cancel(blockedNotificationId(threadId))
    }

    /**
     * Provider truth (U5, M-SP3): the rules, the memberships and every unread text come back; the chat is sorted.
     * C-12: an Undo that fails keeps the notice, so it can be tried again; it never looks done.
     */
    private suspend fun undoMove(context: Context, repository: SpamRepository, undo: BlockUndo, threadId: Long) {
        val receipt = undo.receipt
        if (receipt != null && runCatching { repository.undoBlock(receipt) }.isFailure) return
        if (threadId > 0) runCatching { StoreUnread.restore(context, threadId, undo.unread) }
        if (threadId > 0) sortThread(context, threadId)
        context.notificationManager.cancel(blockedNotificationId(threadId))
    }

    /**
     * Provider truth: the block of [SenderMoves.blockSenders], the chat read and sorted again, its notifications gone
     * (the message and the quiet Spam notice), and the Undo notice. C-3: no receipt (the sender cannot be read, or the
     * write failed) shows a short "could not block" notice with no Undo and keeps the message notification; a user
     * allow rule that still names the sender takes the block back and says why, with no Undo.
     */
    private suspend fun moveToSpam(context: Context, repository: SpamRepository, address: String, threadId: Long) {
        val receipt = runCatching { SenderMoves.blockSenders(repository, listOf(address)) }.getOrNull()?.firstOrNull()
        if (receipt == null) {
            // The sender cannot be read (or the write failed): nothing was blocked, and a short notice says so.
            showNotice(context, threadId, R.string.thread_block_failed)
            return
        }
        if (receipt.stillAllowedBy != null) {
            SenderMoves.undoBlocks(repository, listOf(receipt))
            showNotice(context, threadId, R.string.pt_block_still_allowed)
            return
        }
        if (threadId > 0) SpamChatGate.Known.note(threadId, spam = true)
        context.notificationManager.cancel(threadId.hashCode())
        if (threadId > 0) BlockedNotifier.cancelChat(context, threadId)
        val unread = if (threadId > 0) StoreUnread.of(context, threadId) else UnreadTexts()
        val wasUnread = threadId > 0 && (!unread.isEmpty || runCatching {
            context.conversationsDB.getConversationWithThreadId(threadId)?.read == false
        }.getOrDefault(false))
        if (wasUnread) runCatching { context.markThreadMessagesRead(threadId) }
        if (threadId > 0) sortThread(context, threadId)
        val undo = BlockUndoCodec.encode(BlockUndo(receipt, unread))
        showBlocked(context, address, threadId, listOf(receipt.ruleId).filter { it > 0 }, undo)
    }

    /**
     * C-3: a quiet notice with no Undo when nothing was blocked: [text] says why (the sender cannot be read, or a user
     * allow rule still names it and the block was taken back).
     */
    private fun showNotice(context: Context, threadId: Long, text: Int) {
        val manager = context.notificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notif_block_channel), NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setContentText(context.getString(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(UNDO_VISIBLE_MS)
            .build()
        manager.notify(blockedNotificationId(threadId), notification)
    }

    /** A quiet "Blocked <number>" notice with Undo. It stays for a while, so a wrong tap can be taken back. */
    private fun showBlocked(
        context: Context,
        address: String,
        threadId: Long,
        ruleIds: List<Long>,
        undoReceipt: String? = null,
    ) {
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
            undoReceipt?.let { putExtra(EXTRA_UNDO, it) }
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

        /** Provider truth: the whole block receipt and the unread state, as [BlockUndoCodec] writes them. */
        const val EXTRA_UNDO = "com.glooboo.spamless.extra.BLOCK_UNDO"
        const val CHANNEL_ID = "sender_blocked_undo"

        /** Provider truth: the chat is sorted again after a block or its Undo (tests replace it). */
        @VisibleForTesting
        internal var sortThread: (Context, Long) -> Unit = { context, id ->
            SpamSorting.requestThreads(context, listOf(id))
        }

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

/** What the notification's Undo puts back (provider truth): the block's receipt, and the chat's unread texts. */
internal data class BlockUndo(val receipt: BlockReceipt?, val unread: UnreadTexts = UnreadTexts())

/**
 * Writes a [BlockUndo] into the Undo intent and reads it back, so the Undo of a notification works after the process
 * died. Holds rules and group members only (patterns, notes, keys), never message text. A value that cannot be read
 * gives null, and Undo then removes only the rule ids it has, as in 0.4.14.
 */
internal object BlockUndoCodec {
    fun encode(undo: BlockUndo): String = JSONObject().apply {
        put("sms", JSONArray(undo.unread.sms))
        put("mms", JSONArray(undo.unread.mms))
        undo.receipt?.let { receipt ->
            put("address", receipt.address)
            put("ruleId", receipt.ruleId)
            receipt.stillAllowedBy?.let { put("stillAllowedBy", it) }
            put("rules", JSONArray().apply { receipt.removedRules.forEach { put(rule(it)) } })
            put("members", JSONArray().apply { receipt.removedMembers.forEach { put(member(it)) } })
        }
    }.toString()

    fun decode(text: String): BlockUndo? = runCatching {
        val json = JSONObject(text)
        val receipt = if (json.has("address")) {
            BlockReceipt(
                address = json.getString("address"),
                ruleId = json.getLong("ruleId"),
                removedRules = json.getJSONArray("rules").let { list ->
                    (0 until list.length()).map { rule(list.getJSONObject(it)) }
                },
                removedMembers = json.getJSONArray("members").let { list ->
                    (0 until list.length()).map { member(list.getJSONObject(it)) }
                },
                stillAllowedBy = if (json.has("stillAllowedBy")) json.getLong("stillAllowedBy") else null,
            )
        } else {
            null
        }
        BlockUndo(receipt, UnreadTexts(longs(json.optJSONArray("sms")), longs(json.optJSONArray("mms"))))
    }.getOrNull()

    private fun longs(list: JSONArray?): List<Long> = list?.let { (0 until it.length()).map(it::getLong) }.orEmpty()

    private fun rule(rule: SpamRule) = JSONObject().apply {
        put("id", rule.id)
        put("kind", rule.kind.name)
        put("type", rule.type.name)
        put("pattern", rule.pattern)
        put("note", rule.note)
        put("createdAt", rule.createdAt)
        put("hitCount", rule.hitCount)
        put("lastHit", rule.lastHit)
        put("acknowledged", rule.acknowledged)
    }

    private fun rule(json: JSONObject) = SpamRule(
        id = json.getLong("id"),
        kind = ListKind.valueOf(json.getString("kind")),
        type = RuleType.valueOf(json.getString("type")),
        pattern = json.getString("pattern"),
        note = json.optString("note"),
        createdAt = json.getLong("createdAt"),
        hitCount = json.optInt("hitCount"),
        lastHit = json.optLong("lastHit"),
        acknowledged = json.optBoolean("acknowledged"),
    )

    private fun member(member: AllowGroupMember) = JSONObject().apply {
        put("group", member.group.name)
        put("key", member.senderKey)
        put("address", member.address)
        put("addedAt", member.addedAt)
        put("source", member.source.name)
        put("acknowledged", member.acknowledged)
    }

    private fun member(json: JSONObject) = AllowGroupMember(
        group = AllowGroup.valueOf(json.getString("group")),
        senderKey = json.getString("key"),
        address = json.getString("address"),
        addedAt = json.getLong("addedAt"),
        source = MemberSource.valueOf(json.getString("source")),
        acknowledged = json.getBoolean("acknowledged"),
    )
}
