package com.glooboo.umessage.helpers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.glooboo.umessage.R

enum class ChatSound { Sent, Received }

/** Plays a [ChatSound]; the real one uses a SoundPool, tests use a fake. */
interface ChatSoundOutput {
    fun play(sound: ChatSound)
    fun release() = Unit
}

/** A message that just arrived: [key] says which one, [millis] when it was received. */
data class ChatArrival(val key: String, val millis: Long) {
    constructor(id: Long, isMms: Boolean, millis: Long) : this("$id:$isMms", millis)
}

/**
 * The sounds of an open chat (I65): a short one when a message was sent, one when a message arrives while the chat is
 * on screen. Off unless the switch is on, silent in silent and vibrate mode, never twice for one message, and never
 * for messages that were already there when the chat was opened ([startedAtMillis]). [enabled] and [silent] are read
 * at each use.
 */
class ChatSounds(
    private val enabled: () -> Boolean,
    private val silent: () -> Boolean,
    private val output: ChatSoundOutput,
    private val startedAtMillis: Long = System.currentTimeMillis(),
) {
    private val played = HashSet<String>()

    fun sent() = play(ChatSound.Sent)

    /** One sound for a batch of [arrivals] that were seen, however many messages it holds. */
    fun received(arrivals: Collection<ChatArrival>) {
        val fresh = arrivals.filter { it.millis >= startedAtMillis && played.add(it.key) }
        if (fresh.isNotEmpty()) play(ChatSound.Received)
    }

    fun release() = output.release()

    private fun play(sound: ChatSound) {
        if (enabled() && !silent()) output.play(sound)
    }

    companion object {
        fun create(context: Context): ChatSounds {
            val app = context.applicationContext
            val audio = app.getSystemService(AudioManager::class.java)
            return ChatSounds(
                enabled = { app.composerPrefs.chatSounds },
                silent = { audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL },
                output = SoundPoolOutput(app),
            )
        }
    }
}

/** Short sounds from res/raw through a SoundPool tagged as an instant message, so the system's volume rules apply. */
private class SoundPoolOutput(private val context: Context) : ChatSoundOutput {
    private var pool: SoundPool? = null
    private val ids = HashMap<ChatSound, Int>()
    private var pending: ChatSound? = null

    override fun play(sound: ChatSound) {
        val soundPool = pool ?: createPool().also { pool = it }
        val id = ids[sound]
        if (id == null) {
            pending = sound
            ids[sound] = soundPool.load(context, rawFor(sound), 1)
        } else {
            soundPool.play(id, VOLUME, VOLUME, 1, 0, 1f)
        }
    }

    override fun release() {
        pool?.release()
        pool = null
        ids.clear()
    }

    private fun createPool(): SoundPool {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build().apply {
            // The first play waits for the file to load, then plays it.
            setOnLoadCompleteListener { soundPool, sampleId, status ->
                val wanted = pending
                if (status == 0 && wanted != null && ids[wanted] == sampleId) {
                    pending = null
                    soundPool.play(sampleId, VOLUME, VOLUME, 1, 0, 1f)
                }
            }
        }
    }

    private fun rawFor(sound: ChatSound) = if (sound == ChatSound.Sent) R.raw.chat_sent else R.raw.chat_received

    private companion object {
        const val VOLUME = 0.6f
    }
}
