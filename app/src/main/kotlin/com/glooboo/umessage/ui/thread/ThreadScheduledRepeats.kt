package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.scheduled.repeatNoteRes
import com.glooboo.umessage.models.ThreadItem
import kotlinx.coroutines.runBlocking
import org.fossify.commons.helpers.ensureBackgroundThread

/** B04: the repeat rule of every repeating scheduled message, by message id. Reads the database: background only. */
fun Context.loadScheduledRepeatRules(): Map<Long, RepeatRule> = runCatching {
    runBlocking { getMessagesDB().ScheduledRepeatsDao().getAllRepeating() }
}.getOrDefault(emptyList()).associate { it.messageId to RepeatRule.fromName(it.rule) }

/** The note under a scheduled bubble that repeats ("Repeats yearly"); null for any other message. */
@StringRes
fun repeatNoteFor(message: Message, rules: Map<Long, RepeatRule>): Int? {
    if (!message.isScheduled) return null
    return rules[message.id]?.let { repeatNoteRes(it, message.isHeldScheduled()) }
}

/** The repeat to say next to the time in the schedule banner ("Repeats yearly"); null when it does not repeat. */
@StringRes
fun bannerRepeatNote(rule: RepeatRule): Int? = repeatNoteRes(rule, held = false)

/** Reads the repeats for the scheduled messages among [items] and publishes them for the bubbles. */
fun ThreadViewModel.refreshRepeatRules(activity: Activity, items: List<ThreadItem>) {
    if (items.none { it is Message && it.isScheduled }) {
        if (scheduledRepeatRules.isNotEmpty()) scheduledRepeatRules = emptyMap()
        return
    }
    ensureBackgroundThread {
        val rules = activity.loadScheduledRepeatRules()
        activity.runOnUiThread { scheduledRepeatRules = rules }
    }
}
