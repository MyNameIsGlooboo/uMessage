package com.glooboo.umessage.ui.conversations

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.ui.components.showUndo
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.extensions.removeAllArchivedConversations
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

data class ArchivedUiState(val rows: List<ConversationRow> = emptyList(), val loading: Boolean = true)

/** Archived conversations for the Archived tab; unarchiving or deleting keeps the proven data calls. */
class ArchivedViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val _state = MutableStateFlow(ArchivedUiState())
    val state: StateFlow<ArchivedUiState> = _state.asStateFlow()

    init {
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    fun load() {
        ensureBackgroundThread {
            val conversations = try {
                app.conversationsDB.getAllArchived()
            } catch (_: Exception) {
                emptyList()
            }
            val drafts = runCatching { app.getAllDrafts() }.getOrDefault(HashMap())
            val pinned = app.config.pinnedConversations
            // B06: hidden chats stay out of the archive too, until they are unlocked.
            val rows = HiddenChats.get(app).withoutHidden(conversations) { it.threadId }
                .sortedWith(
                    compareByDescending<Conversation> { pinned.contains(it.threadId.toString()) }
                        .thenByDescending { it.date },
                )
                .map { it.toFolderRow(app, drafts, pinned) }
            _state.value = ArchivedUiState(rows = rows, loading = false)
        }
    }

    fun unarchive(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach { app.updateConversationArchivedStatus(it, false) }
            refreshConversations()
        }
    }

    /** Undo of [unarchive]. */
    fun archiveAgain(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach { app.updateConversationArchivedStatus(it, true) }
            refreshConversations()
        }
    }

    /** CL-32: archived promotions pile up unread; this marks every archived chat read. */
    fun markAllRead() {
        ensureBackgroundThread {
            _state.value.rows.filter { it.unread }.forEach { runCatching { app.markThreadMessagesRead(it.threadId) } }
            refreshConversations()
        }
    }

    fun delete(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach {
                app.deleteConversation(it)
                app.notificationManager.cancel(it.hashCode())
            }
            refreshConversations()
        }
    }

    fun deleteAll() {
        app.removeAllArchivedConversations { load() }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        load()
    }
}

/**
 * Archived tab: the shared folder screen without a back arrow; reloads whenever it comes back on screen. Unarchiving
 * says where the chats went and offers Undo; a one-line intro explains what the archive does until it is dismissed.
 */
@Composable
fun ArchivedRoute(onOpen: (ConversationRow) -> Unit, onBackToMessages: (() -> Unit)? = null) {
    val viewModel: ArchivedViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember(context) { NavPrefs.get(context) }
    val introDismissed by prefs.archivedIntroDismissedFlow.collectAsStateWithLifecycle(initialValue = true)
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    ConversationFolderScreen(
        folder = ConversationFolder.Archive,
        rows = state.rows,
        loading = state.loading,
        onBack = null,
        onOpen = onOpen,
        onRestore = { ids ->
            viewModel.unarchive(ids)
            scope.launch {
                snackbar.showUndo(
                    message = resources.getQuantityString(R.plurals.nav_archived_moved, ids.size, ids.size),
                    undoLabel = resources.getString(R.string.convlist_undo),
                    onUndo = { viewModel.archiveAgain(ids) },
                )
            }
        },
        onDelete = viewModel::delete,
        onEmpty = viewModel::deleteAll,
        snackbarHostState = snackbar,
        onBackToMessages = onBackToMessages,
        onMarkAllRead = viewModel::markAllRead,
        intro = if (introDismissed) null else {
            { ArchivedIntro(onDismiss = { prefs.archivedIntroDismissed = true }) }
        },
    )
}

@Composable
private fun ArchivedIntro(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.nav_archived_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.nav_archived_intro_dismiss))
            }
        }
    }
}
