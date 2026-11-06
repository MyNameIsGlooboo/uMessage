package com.glooboo.umessage.ui.thread

import android.content.Intent
import android.text.format.DateUtils
import androidx.core.content.pm.ShortcutManagerCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.SettingsActivity
import com.glooboo.umessage.extensions.shortcutHelper
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.MuteDuration
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.ui.components.ChoiceItem
import com.glooboo.umessage.ui.components.showChoiceDialog
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.settings.SettingsPage
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.toast

/**
 * The thread menu's Mute and Hide entries (N05, B06), also used by Conversation details for Mute. [onChange] runs on
 * the main thread after a change so the screen can refresh its menu and summaries; [onHidden] after a chat was hidden
 * (the thread screen closes: the chat is no longer shown).
 */
class ThreadPrivacyActions(
    private val activity: BaseSimpleActivity,
    private val threadId: () -> Long,
    private val onChange: () -> Unit,
    private val onHidden: () -> Unit = {},
) {
    private val mutes = MutePrefs.get(activity)
    private val hiddenChats = HiddenChats.get(activity)

    fun isMuted(): Boolean = mutes.isMuted(threadId())

    fun isHiddenChat(): Boolean = hiddenChats.isChosen(threadId())

    /** "Off", "Always" or "Until <time>": what Conversation details shows under Mute. */
    fun muteSummary(): String {
        val until = mutes.mutedUntil(threadId())
        return when (until) {
            0L -> activity.getString(R.string.mute_off)
            MutePrefs.ALWAYS -> activity.getString(R.string.mute_always)
            else -> activity.getString(R.string.mute_until, DateUtils.formatDateTime(activity, until, MUTE_TIME_FLAGS))
        }
    }

    /** Mutes after asking for how long, or unmutes a muted chat. */
    fun toggleMute() = if (isMuted()) unmute() else askMute()

    fun askMute() {
        val items = listOf(
            ChoiceItem(activity.getString(R.string.mute_1_hour), MuteDuration.OneHour),
            ChoiceItem(activity.getString(R.string.mute_8_hours), MuteDuration.EightHours),
            ChoiceItem(activity.getString(R.string.mute_1_week), MuteDuration.OneWeek),
            ChoiceItem(activity.getString(R.string.mute_always), MuteDuration.Always),
        )
        activity.showChoiceDialog(items, title = activity.getString(R.string.mute_title)) { duration ->
            mutes.mute(threadId(), duration)
            onChange()
        }
    }

    fun unmute() {
        mutes.unmute(threadId())
        onChange()
    }

    /** Hides the chat behind the app lock; without an app lock it first offers to set one up. */
    fun hide() {
        if (!activity.baseConfig.isAppPasswordProtectionOn) {
            activity.showConfirmDialog(
                message = activity.getString(R.string.hidden_chats_need_lock),
                confirmLabel = activity.getString(R.string.hidden_chats_set_lock),
                dismissLabel = activity.getString(R.string.screens_cancel),
            ) { openAppLockSettings() }
            return
        }
        activity.showConfirmDialog(
            message = activity.getString(R.string.hidden_chats_confirm),
            confirmLabel = activity.getString(R.string.hidden_chats_menu_hide),
            dismissLabel = activity.getString(R.string.screens_cancel),
        ) { hideNow() }
    }

    fun unhide() {
        hiddenChats.unhide(threadId())
        onChange()
    }

    private fun hideNow() {
        val id = threadId()
        hiddenChats.hide(id)
        // What the chat already left outside the app goes: its notification and its launcher shortcuts.
        activity.notificationManager.cancel(id.hashCode())
        activity.shortcutHelper.removeShortcutForThread(id)
        runCatching { ShortcutManagerCompat.disableShortcuts(activity, listOf(id.toString()), null) }
        HiddenChatsSession.lock()
        activity.toast(R.string.hidden_chats_done)
        onHidden()
    }

    private fun openAppLockSettings() {
        val settings = Intent(activity, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.AppLock.name)
        activity.startActivity(settings)
    }

    private companion object {
        const val MUTE_TIME_FLAGS =
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
}
