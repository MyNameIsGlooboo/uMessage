package com.glooboo.umessage.ui.thread

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.ScheduleMessageDialog
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.messaging.bookNextRepeat
import com.glooboo.umessage.messaging.deleteScheduledRepeat
import com.glooboo.umessage.messaging.getScheduledRepeat
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.ChoiceItem
import com.glooboo.umessage.ui.components.askYesNo
import com.glooboo.umessage.ui.components.showChoiceDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.openRequestExactAlarmSettings
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * The scheduling dialogs of the thread screen: the time picker (after offering "Alarms & reminders" when needed),
 * the composer's schedule mode, and the choices on a scheduled bubble (edit, send now, delete; a held message only
 * offers retry or delete).
 *
 * [cancel] deletes a scheduled message (keeping its attachment copies when the composer still shows them);
 * [sendNow] sends one at once; [loadIntoComposer] empties the composer and puts the message's text and
 * attachments in it.
 */
class ThreadScheduleUi(
    private val activity: BaseSimpleActivity,
    private val vm: ThreadViewModel,
    private val cancel: (messageId: Long, keepAttachmentFiles: Boolean) -> Unit,
    private val sendNow: (Message) -> Unit,
    private val loadIntoComposer: (Message) -> Unit,
) {
    /** Scheduling to continue with once the user is back from the "Alarms & reminders" setting. */
    private var afterExactAlarmSettings: (() -> Unit)? = null

    /** Call from onResume: continues scheduling after the user came back from the setting. */
    fun onResume() {
        afterExactAlarmSettings?.let {
            afterExactAlarmSettings = null
            it()
        }
    }

    /**
     * Without "Alarms & reminders" (off by default on Android 14+) scheduling still works with an inexact alarm, so
     * this only offers the setting: it used to wait for the permission and do nothing until it was granted. Back
     * from the setting, scheduling continues whatever the user chose there. USE_EXACT_ALARM is not used: Play allows
     * it only for alarm-clock and calendar apps. B12: the offer is made once; after that a short toast on saving says
     * the time is approximate.
     */
    private fun askForExactAlarmPermissionIfNeeded(callback: () -> Unit) {
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Always true before Android 12, where the setting does not exist.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            AlarmManagerCompat.canScheduleExactAlarms(alarmManager)
        val prefs = activity.threadPrefs
        if (exact || prefs.exactAlarmAsked) {
            callback()
            return
        }
        prefs.exactAlarmAsked = true
        activity.showExactAlarmDialog(
            onOpenSettings = {
                afterExactAlarmSettings = callback
                try {
                    activity.openRequestExactAlarmSettings(BuildConfig.APPLICATION_ID)
                } catch (_: Exception) {
                    afterExactAlarmSettings = null
                    callback()
                }
            },
            onScheduleAnyway = callback,
        )
    }

    /** Opens the time picker, at [originalMillis] when the message already has a time. */
    fun pickTime(originalMillis: Long? = null) {
        askForExactAlarmPermissionIfNeeded {
            val repeat = RepeatRule.fromName(vm.scheduledRepeat)
            ScheduleMessageDialog(activity, originalMillis, repeat) { newMillis, newRepeat ->
                vm.scheduledRepeat = newRepeat.takeUnless { it == RepeatRule.NONE }?.name
                if (newMillis != null) show(newMillis)
            }
        }
    }

    /** Puts the composer in "schedule" mode for [millis]; the time lives in the view model's saved state. */
    fun show(millis: Long) {
        vm.scheduledMillis = millis
        val time = scheduleLabel(activity, millis, jalali = activity.w3cPrefs.dateStyle != DateStyle.GREGORIAN)
        val repeatNote = bannerRepeatNote(RepeatRule.fromName(vm.scheduledRepeat))
        vm.scheduledLabel = if (repeatNote == null) time else "$time · ${activity.getString(repeatNote)}"
    }

    /** "x" on the schedule banner: back to sending now; a scheduled message being edited is deleted. */
    fun discard() {
        val editedId = vm.scheduledMessageId
        vm.clearSchedule()
        if (editedId != null) cancel(editedId, true)
    }

    /** A tap on a scheduled bubble. */
    fun showOptions(message: Message) {
        if (message.isHeldScheduled()) {
            // Its send failed: it waits for the user, who can only try again or throw it away.
            val heldItems = listOf(
                ChoiceItem(activity.getString(R.string.ui_retry_send), TYPE_SEND),
                ChoiceItem(activity.getString(org.fossify.commons.R.string.delete), TYPE_DELETE),
            )
            activity.showChoiceDialog(heldItems, title = activity.getString(R.string.thread_scheduled_not_sent)) { any ->
                when (any) {
                    TYPE_DELETE -> cancel(message.id, false)
                    TYPE_SEND -> sendNow(message)
                }
            }
            return
        }
        val items = listOf(
            ChoiceItem(activity.getString(R.string.update_message), TYPE_EDIT),
            ChoiceItem(activity.getString(R.string.send_now), TYPE_SEND),
            ChoiceItem(activity.getString(org.fossify.commons.R.string.delete), TYPE_DELETE),
        )
        activity.showChoiceDialog(items, title = activity.getString(R.string.scheduled_message)) { any ->
            when (any) {
                TYPE_DELETE -> confirmDelete(message)
                TYPE_EDIT -> confirmEdit(message)
                TYPE_SEND -> sendOccurrenceNow(message)
            }
        }
    }

    /** Send now on a repeating message sends this one; the repeat goes on, so the next copy is booked first. */
    private fun sendOccurrenceNow(message: Message) {
        ensureBackgroundThread {
            // This copy becomes a one-off, so a failed send held for Retry cannot book a second next copy.
            if (runCatching { activity.bookNextRepeat(message) }.getOrNull() != null) {
                activity.deleteScheduledRepeat(message.id)
            }
            activity.runOnUiThread { sendNow(message) }
        }
    }

    /** A repeating message asks whether only this one goes or all the later ones too (B04). */
    private fun confirmDelete(message: Message) {
        ensureBackgroundThread {
            val repeating = runCatching { activity.getScheduledRepeat(message.id) }.getOrNull() != null
            if (!repeating) {
                cancel(message.id, false)
                return@ensureBackgroundThread
            }
            activity.runOnUiThread {
                val items = listOf(
                    ChoiceItem(activity.getString(R.string.scheduled_delete_this_one), TYPE_DELETE_THIS),
                    ChoiceItem(activity.getString(R.string.scheduled_delete_all_future), TYPE_DELETE),
                )
                activity.showChoiceDialog(items, title = activity.getString(R.string.scheduled_delete_repeating)) {
                    if (it == TYPE_DELETE) {
                        cancel(message.id, false)
                    } else {
                        // Books the next copy first, then deletes this one: the repeat goes on.
                        ensureBackgroundThread {
                            runCatching { activity.bookNextRepeat(message) }
                            cancel(message.id, false)
                        }
                    }
                }
            }
        }
    }

    /** Editing loads the scheduled message into the composer; ask first when that would replace a draft. */
    private fun confirmEdit(message: Message) {
        val hasDraft = vm.text.text.isNotBlank() || vm.attachments.isNotEmpty()
        if (hasDraft && vm.scheduledMessageId != message.id) {
            activity.askYesNo(activity.getString(R.string.thread_edit_scheduled_replaces_draft)) { edit(message) }
        } else {
            edit(message)
        }
    }

    private fun edit(message: Message) {
        loadIntoComposer(message)
        vm.scheduledMessageId = message.id
        show(message.millis())
        ensureBackgroundThread {
            val repeat = runCatching { activity.getScheduledRepeat(message.id) }.getOrNull()
            activity.runOnUiThread {
                vm.scheduledRepeat = repeat?.rule
                if (vm.scheduledMessageId == message.id) show(vm.scheduledMillis ?: message.millis())
            }
        }
    }

    private companion object {
        const val TYPE_EDIT = 14
        const val TYPE_SEND = 15
        const val TYPE_DELETE = 16
        const val TYPE_DELETE_THIS = 17
    }
}
