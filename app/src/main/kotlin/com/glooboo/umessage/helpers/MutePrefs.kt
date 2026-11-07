package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** N05: how long a conversation is muted from its menu. */
enum class MuteDuration(private val millis: Long) {
    OneHour(HOUR_MS),
    EightHours(EIGHT_HOURS_MS),
    OneWeek(WEEK_MS),
    Always(0L),
    ;

    /** When a mute started at [nowMs] ends; [MutePrefs.ALWAYS] for [Always]. */
    fun endsAt(nowMs: Long): Long = if (this == Always) MutePrefs.ALWAYS else nowMs + millis
}

private const val HOUR_MS = 60L * 60 * 1000
private const val EIGHT_HOURS_MS = 8 * HOUR_MS
private const val WEEK_MS = 7 * 24 * HOUR_MS

/**
 * N05: conversations muted from the thread menu or Conversation details, each with the time the mute ends. A muted chat
 * still counts as unread and still posts its notification, but quietly (see [MutedChannel]). An ended mute is simply
 * ignored when read. In its own preferences file, one key per thread; muting Always keeps the entry until Unmute.
 */
class MutePrefs(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** When the mute of [threadId] ends, or 0 when it is not muted (or the mute has ended). */
    fun mutedUntil(threadId: Long): Long {
        val until = prefs.getLong(key(threadId), 0L)
        return if (until > now()) until else 0L
    }

    fun isMuted(threadId: Long): Boolean = mutedUntil(threadId) != 0L

    fun mute(threadId: Long, duration: MuteDuration) {
        prefs.edit {
            dropEnded()
            putLong(key(threadId), duration.endsAt(now()))
        }
    }

    fun unmute(threadId: Long) = prefs.edit { remove(key(threadId)) }

    /** Every mute that has not ended: thread id to the time it ends ([ALWAYS] for one that never ends). */
    fun activeMutes(): Map<Long, Long> {
        val time = now()
        return prefs.all.mapNotNull { (k, v) ->
            val id = if (k.startsWith(PREFIX)) k.removePrefix(PREFIX).toLongOrNull() else null
            if (id != null && v is Long && v > time) id to v else null
        }.toMap()
    }

    /** Mutes [threadId] until [until] (a restored mute); a past time or a later mute already set changes nothing. */
    fun muteUntil(threadId: Long, until: Long): Boolean {
        if (until <= now() || mutedUntil(threadId) >= until) return false
        prefs.edit {
            dropEnded()
            putLong(key(threadId), until)
        }
        return true
    }

    private fun SharedPreferences.Editor.dropEnded() {
        val time = now()
        prefs.all.filter { (k, v) -> k.startsWith(PREFIX) && v is Long && v <= time }.keys.forEach { remove(it) }
    }

    private fun key(threadId: Long) = "$PREFIX$threadId"

    companion object {
        /** The end time of a mute that never ends. */
        const val ALWAYS = Long.MAX_VALUE

        private const val PREFS_NAME = "spamless_mute"
        private const val PREFIX = "until_"

        fun get(context: Context): MutePrefs =
            MutePrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
