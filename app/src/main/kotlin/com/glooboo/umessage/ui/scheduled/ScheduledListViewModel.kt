package com.glooboo.umessage.ui.scheduled

import android.app.Application
import android.content.Intent
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.messaging.bookNextRepeat
import com.glooboo.umessage.messaging.cancelScheduleSendPendingIntent
import com.glooboo.umessage.messaging.deleteScheduledAttachments
import com.glooboo.umessage.messaging.deleteScheduledRepeat
import com.glooboo.umessage.messaging.saveScheduledRepeat
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.receivers.ScheduledMessageReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One scheduled message in the list. [held]: its send failed, so it waits for the user (and its repeat is paused). */
data class ScheduledRow(
    val id: Long,
    val threadId: Long,
    val title: String,
    val text: String,
    val millis: Long,
    val held: Boolean,
    val repeat: RepeatRule,
    val approximate: Boolean,
)

/** The rows of one day, in time order. */
data class ScheduledDay(val date: LocalDate, val rows: List<ScheduledRow>)

/** The screen: the messages that could not be sent first, then the pending ones, a section for each day. */
data class ScheduledListState(
    val notSent: List<ScheduledRow> = emptyList(),
    val days: List<ScheduledDay> = emptyList(),
    val loading: Boolean = true,
) {
    val count: Int get() = notSent.size + days.sumOf { it.rows.size }
}

/** Sorts [rows] by time, puts the held ones in their own list and groups the rest by their day in [zone]. */
fun groupScheduled(rows: List<ScheduledRow>, zone: ZoneId = ZoneId.systemDefault()): ScheduledListState {
    val (held, pending) = rows.partition { it.held }
    val days = pending
        .sortedBy { it.millis }
        .groupBy { Instant.ofEpochMilli(it.millis).atZone(zone).toLocalDate() }
        .map { (date, dayRows) -> ScheduledDay(date, dayRows) }
    return ScheduledListState(notSent = held.sortedBy { it.millis }, days = days, loading = false)
}

/** I25: every scheduled message of every conversation, with edit time, send now and delete. */
class ScheduledListViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val reader = ScheduledStateReader(application)
    private val _state = MutableStateFlow(ScheduledListState())
    val state: StateFlow<ScheduledListState> = _state.asStateFlow()

    init {
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    fun load() {
        ensureBackgroundThread { _state.value = reader.read() }
    }

    /** Moves [row] to [millis] and sets how it repeats; a message that was not sent is queued again. */
    fun reschedule(row: ScheduledRow, millis: Long, repeat: RepeatRule) {
        ensureBackgroundThread {
            val message = findScheduled(row.id) ?: return@ensureBackgroundThread
            val moved = message.copy(date = (millis / 1000).toInt(), type = Telephony.Sms.MESSAGE_TYPE_QUEUED)
            app.messagesDB.insertOrUpdate(moved)
            app.saveScheduledRepeat(moved, repeat)
            app.scheduleMessage(moved)
            afterChange()
        }
    }

    /** Sends [row] at once, the way its alarm would; the message and its alarm are gone afterwards. */
    fun sendNow(row: ScheduledRow) {
        ensureBackgroundThread {
            if (findScheduled(row.id) == null) return@ensureBackgroundThread
            // A message that failed is held until the user retries; this is that retry.
            runCatching { app.messagesDB.updateScheduledType(row.id, Telephony.Sms.MESSAGE_TYPE_QUEUED) }
            app.cancelScheduleSendPendingIntent(row.id)
            val intent = Intent().putExtra(SCHEDULED_MESSAGE_ID, row.id)
            ScheduledMessageReceiver().handleIntent(app, intent)
            afterChange()
        }
    }

    /** Deletes [row]; with [onlyThis] a repeating message keeps coming back, otherwise it is gone for good. */
    fun delete(row: ScheduledRow, onlyThis: Boolean) {
        ensureBackgroundThread {
            val message = findScheduled(row.id)
            if (onlyThis && message != null) runCatching { app.bookNextRepeat(message) }
            app.messagesDB.deleteScheduledMessage(row.id)
            app.cancelScheduleSendPendingIntent(row.id)
            app.deleteScheduledRepeat(row.id)
            app.deleteScheduledAttachments(row.id)
            message?.let { dropEmptyTemporaryThread(it.threadId) }
            afterChange()
        }
    }

    /** A conversation made only to hold a scheduled message goes with its last one. */
    private fun dropEmptyTemporaryThread(threadId: Long) {
        runCatching {
            val temporary = app.conversationsDB.getConversationWithThreadId(threadId)?.isScheduled == true
            if (temporary && app.messagesDB.getScheduledThreadMessages(threadId).isEmpty()) {
                app.conversationsDB.deleteThreadId(threadId)
            }
        }
    }

    private fun findScheduled(id: Long): Message? =
        runCatching { app.messagesDB.getAllScheduledMessages().firstOrNull { it.id == id } }.getOrNull()

    private fun afterChange() {
        _state.value = reader.read()
        refreshMessages()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessagesChanged(@Suppress("unused") event: Events.RefreshMessages) {
        load()
    }
}
