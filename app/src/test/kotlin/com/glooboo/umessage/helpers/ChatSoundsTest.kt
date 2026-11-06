package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I65: sounds only when switched on, never in silent mode, once per message, and not for old messages. */
class ChatSoundsTest {
    private class FakeOutput : ChatSoundOutput {
        val played = ArrayList<ChatSound>()
        override fun play(sound: ChatSound) {
            played.add(sound)
        }
    }

    private val output = FakeOutput()
    private var enabled = true
    private var silent = false
    private val sounds = ChatSounds({ enabled }, { silent }, output, startedAtMillis = 1_000L)

    @Test
    fun sentPlaysOnlyWhenEnabled() {
        sounds.sent()
        enabled = false
        sounds.sent()
        assertThat(output.played).containsExactly(ChatSound.Sent)
    }

    @Test
    fun silentModeStaysQuiet() {
        silent = true
        sounds.sent()
        sounds.received(listOf(ChatArrival(1L, false, 2_000L)))
        assertThat(output.played).isEmpty()
    }

    @Test
    fun aReceivedMessageSoundsOnceEvenWhenSeenAgain() {
        val arrival = ChatArrival(7L, false, 2_000L)
        sounds.received(listOf(arrival))
        sounds.received(listOf(arrival))
        assertThat(output.played).containsExactly(ChatSound.Received)
    }

    @Test
    fun aBatchSoundsOnceAndMessagesFromBeforeTheChatOpenedStayQuiet() {
        sounds.received(listOf(ChatArrival(1L, false, 500L), ChatArrival(2L, true, 900L)))
        assertThat(output.played).isEmpty()
        sounds.received(listOf(ChatArrival(3L, false, 2_000L), ChatArrival(4L, false, 2_100L)))
        assertThat(output.played).containsExactly(ChatSound.Received)
    }

    @Test
    fun smsAndMmsWithTheSameIdAreDifferentMessages() {
        sounds.received(listOf(ChatArrival(5L, false, 2_000L)))
        sounds.received(listOf(ChatArrival(5L, true, 2_000L)))
        assertThat(output.played).hasSize(2)
    }
}
