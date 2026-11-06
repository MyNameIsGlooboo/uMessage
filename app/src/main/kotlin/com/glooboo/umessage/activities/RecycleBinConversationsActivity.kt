package com.glooboo.umessage.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.emptyMessagesRecycleBin
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.extensions.restoreAllMessagesFromRecycleBinForConversation
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.IS_RECYCLE_BIN
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.ui.conversations.ConversationFolder
import com.glooboo.umessage.ui.conversations.ConversationFolderScreen
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.toFolderRow
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** Conversations that still hold messages in the Recycle Bin: restore or delete them for good. */
class RecycleBinConversationsActivity : ComposeSimpleActivity() {
    private var rows by mutableStateOf<List<ConversationRow>>(emptyList())
    private var loading by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUMessageContent {
            ConversationFolderScreen(
                folder = ConversationFolder.RecycleBin,
                rows = rows,
                loading = loading,
                onBack = ::finish,
                onOpen = ::openThread,
                onRestore = ::restore,
                onDelete = ::delete,
                onEmpty = {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                        load()
                    }
                },
            )
        }
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun load() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getAllWithMessagesInRecycleBin()
            } catch (_: Exception) {
                emptyList()
            }
            val drafts = runCatching { getAllDrafts() }.getOrDefault(HashMap())
            val pinned = config.pinnedConversations
            // B06: hidden chats stay out of the recycle bin too, until they are unlocked.
            val built = HiddenChats.get(this).withoutHidden(conversations) { it.threadId }
                .sortedWith(compareByDescending<Conversation> { pinned.contains(it.threadId.toString()) }.thenByDescending { it.date })
                .map { it.toFolderRow(this, drafts, pinned) }
            runOnUiThread {
                rows = built
                loading = false
            }
        }
    }

    private fun restore(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach { restoreAllMessagesFromRecycleBinForConversation(it) }
            refreshConversations()
        }
    }

    private fun delete(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach {
                deleteConversation(it)
                notificationManager.cancel(it.hashCode())
            }
            refreshConversations()
        }
    }

    private fun openThread(row: ConversationRow) {
        startActivity(
            Intent(this, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, row.threadId)
                putExtra(THREAD_TITLE, row.title)
                putExtra(IS_RECYCLE_BIN, true)
            },
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        load()
    }
}
