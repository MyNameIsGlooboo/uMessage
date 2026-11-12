package com.glooboo.umessage.activities

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.glooboo.umessage.extensions.dialNumber
import com.glooboo.umessage.extensions.launchConversationDetails
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.ConversationsCallbacks
import com.glooboo.umessage.ui.conversations.ConversationsEffect
import com.glooboo.umessage.ui.conversations.ConversationsViewModel
import com.glooboo.umessage.ui.main.MainNavigation
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.spam.ForwardingActivity
import com.glooboo.umessage.ui.spam.RulesActivity
import com.glooboo.umessage.ui.spam.SpamSettingsActivity
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.KEY_PHONE

/** Everything the conversation list opens: threads, other screens, and the apps its row actions hand off to. */
class MainRoutes(private val activity: BaseSimpleActivity) {

    /** The bottom-bar tabs' links to other screens. */
    fun navigation() = MainNavigation(
        onSettings = ::openSettings,
        onSpamRules = { activity.startActivity(Intent(activity, RulesActivity::class.java)) },
        onManageForwardRules = {
            if (ForwardingAvailability.isAvailable(activity)) {
                activity.startActivity(ForwardingActivity.intent(activity))
            }
        },
        onSpamSettings = { activity.startActivity(Intent(activity, SpamSettingsActivity::class.java)) },
        onOpenArchived = { row -> openThread(row.threadId, row.title) },
        onOpenThread = { threadId, title -> openThread(threadId, title) },
        onStarred = { activity.startActivity(Intent(activity, StarredActivity::class.java)) },
        onScheduled = { activity.startActivity(Intent(activity, ScheduledListActivity::class.java)) },
        onRecycleBin = { activity.startActivity(Intent(activity, RecycleBinConversationsActivity::class.java)) },
    )

    /** The list's actions: opening goes through here, everything else to [viewModel]. */
    fun conversationsCallbacks(viewModel: ConversationsViewModel) = ConversationsCallbacks(
        onOpen = { row: ConversationRow ->
            activity.hideKeyboard()
            openThread(row.threadId, row.title)
        },
        onToggleSelect = viewModel::toggleSelect,
        onStartSelect = viewModel::startSelect,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        onPin = viewModel::pinSelected,
        onMarkRead = viewModel::markSelectedRead,
        onMarkAllRead = viewModel::markAllRead,
        onUndoMarkAllRead = viewModel::undoMarkAllRead,
        onArchiveSelected = viewModel::archiveSelected,
        onDeleteSelected = viewModel::deleteSelected,
        onArchiveOne = viewModel::archiveOne,
        onDeleteOne = viewModel::deleteOne,
        onConfirmDelete = viewModel::confirmDelete,
        onCancelDelete = viewModel::cancelDelete,
        onBlockSelected = viewModel::blockSelected,
        onBlockOne = viewModel::blockOne,
        onReportSpamSelected = viewModel::reportSpamSelected,
        onUndoReportSpam = viewModel::undoReportSpam,
        onCommitReportSpam = viewModel::commitReportSpam,
        onAddContact = viewModel::addToContact,
        onCopyNumber = viewModel::copyNumber,
        onDial = viewModel::dial,
        onRename = viewModel::rename,
        onDetails = viewModel::showDetails,
        onUndoDelete = viewModel::undoDelete,
        onCommitDelete = viewModel::commitDelete,
        onUndoArchive = viewModel::undoArchive,
        onNewConversation = ::openNewConversation,
    )

    /** A search result opens its chat, scrolled to the message when it is one. */
    fun openSearchResult(result: SearchResult) {
        activity.hideKeyboard()
        openThread(result.threadId, result.title, MessageKey(result.messageId, result.isMMS), result.number)
    }

    fun handle(effect: ConversationsEffect) {
        when (effect) {
            is ConversationsEffect.Dial -> activity.dialNumber(effect.number)
            is ConversationsEffect.Details -> activity.launchConversationDetails(effect.threadId)
            is ConversationsEffect.AddToContact -> Intent().apply {
                action = Intent.ACTION_INSERT_OR_EDIT
                type = "vnd.android.cursor.item/contact"
                putExtra(KEY_PHONE, effect.number)
                activity.launchActivityIntent(this)
            }
        }
    }

    /** This app's page in Android settings, where a denied permission can be granted. */
    fun openAppSettings() {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        }.onFailure { activity.toast(org.fossify.commons.R.string.no_app_found) }
    }

    private fun openThread(threadId: Long, title: String, searched: MessageKey? = null, number: String = "") {
        Intent(activity, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(THREAD_TITLE, title)
            if (number.isNotEmpty()) putExtra(THREAD_NUMBER, number)
            if (searched != null) {
                putExtra(SEARCHED_MESSAGE_ID, searched.id)
                putExtra(SEARCHED_MESSAGE_IS_MMS, searched.isMMS)
            }
            activity.startActivity(this)
        }
    }

    private fun openNewConversation() {
        activity.hideKeyboard()
        activity.startActivity(Intent(activity, NewConversationActivity::class.java))
    }

    private fun openSettings() {
        activity.hideKeyboard()
        activity.startActivity(Intent(activity.applicationContext, SettingsActivity::class.java))
    }
}
