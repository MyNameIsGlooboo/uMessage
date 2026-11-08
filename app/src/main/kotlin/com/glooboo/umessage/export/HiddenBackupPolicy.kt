package com.glooboo.umessage.export

import android.content.Context
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession

/**
 * Q14: what a full backup does with the chats hidden behind the app lock. Hidden chats are protected by the lock, so a
 * full backup (no chats chosen) started while they are locked first asks the user: unlock (the app's own lock screen)
 * to include them, or skip them. The decision is made before the backup service starts and handed to it as a flag, so
 * the service itself never prompts.
 */
object HiddenBackupPolicy {
    enum class Decision {
        /** Back up as usual, hidden chats included (or nothing hidden is in play). */
        INCLUDE,

        /** The hidden chats are locked: ask the user to unlock or to skip them. */
        ASK,

        /** The user chose to back up without the hidden chats. */
        SKIP,
    }

    /**
     * [fullScope]: no chats were chosen. [hiddenCount]: how many chats the user hid. [lockOn]: an app lock is on.
     * [sessionUnlocked]: the hidden chats are unlocked right now (a successful unlock turns this on, a failed or
     * cancelled one leaves it off, so the user is asked again). [skipChosen]: the user picked "Skip hidden chats".
     */
    fun decide(fullScope: Boolean, hiddenCount: Int, lockOn: Boolean, sessionUnlocked: Boolean, skipChosen: Boolean) =
        when {
            !fullScope || hiddenCount == 0 || !lockOn || sessionUnlocked -> Decision.INCLUDE
            skipChosen -> Decision.SKIP
            else -> Decision.ASK
        }

    /** [decide] for the state of the app right now. */
    fun decideNow(context: Context, fullScope: Boolean, skipChosen: Boolean): Decision {
        val hidden = HiddenChats.get(context)
        val unlocked = HiddenChatsSession.isUnlocked()
        return decide(fullScope, hidden.chosenIds().size, hidden.isLockOn(), unlocked, skipChosen)
    }
}
