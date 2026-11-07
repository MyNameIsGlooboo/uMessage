package com.glooboo.umessage.spam.data

import android.content.Context
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.mms.BlockedMmsStore

/**
 * Android side effects of the repository. Kept behind an interface so the repository (a process-wide singleton)
 * holds no Context of its own.
 */
interface SpamPlatform {
    fun notifyBlocked(message: BlockedMessage)

    /** I5: removes the notifications of blocked messages that were restored or deleted. */
    fun cancelBlockedNotifications(ids: Collection<Long>) = Unit

    /**
     * Puts a restored message in the inbox. [pending] is already in the pending_sms table, so a failure here
     * loses nothing: the row is replayed later. Returns the system SMS id it wrote (0 when the message is there but
     * its id is not known), or null when delivery failed.
     */
    fun deliverRestored(pending: PendingSmsEntity): Long?

    /**
     * Puts a blocked MMS whose content is kept ([BlockedMessage.hasStoredMedia]) in the inbox, pictures included.
     * Returns the system MMS id it wrote, or null when it could not; the caller then keeps the blocked row and its
     * files.
     */
    fun deliverRestoredMms(message: BlockedMessage): Long? = null

    /**
     * Undo of a restore: deletes the system SMS (or MMS, when [isMms]) [id] that [deliverRestored] or
     * [deliverRestoredMms] wrote, and updates the chat list. True when it is gone (or already was), false when the
     * provider could not be changed.
     */
    fun removeRestored(id: Long, isMms: Boolean): Boolean = false

    /** Deletes kept MMS content by the names the rows stored. */
    fun deleteMedia(names: Collection<String>) = Unit

    /** Deletes kept MMS content that no row refers to (left behind by a crash). */
    fun sweepMedia(keep: Set<String>) = Unit
}

class AndroidSpamPlatform(context: Context) : SpamPlatform {
    private val appContext = context.applicationContext

    override fun notifyBlocked(message: BlockedMessage) = BlockedNotifier.show(appContext, message)

    override fun cancelBlockedNotifications(ids: Collection<Long>) = BlockedNotifier.cancel(appContext, ids)

    override fun deliverRestored(pending: PendingSmsEntity): Long? = try {
        val id = IncomingSmsProcessor.deliver(appContext, pending, NotifyContext.NONE)
        PendingSmsQueue.done(appContext, pending.id)
        id.coerceAtLeast(0L)
    } catch (_: Throwable) {
        null
    }

    override fun deliverRestoredMms(message: BlockedMessage): Long? = try {
        RestoredMmsDelivery.deliver(appContext, message)
    } catch (_: Throwable) {
        null
    }

    override fun removeRestored(id: Long, isMms: Boolean): Boolean = RestoredRemoval.remove(appContext, id, isMms)

    override fun deleteMedia(names: Collection<String>) {
        runCatching { BlockedMmsStore.delete(appContext, names) }
    }

    /**
     * Skipped while a spamless.db backup from a downgrade waits to be merged back: its rows may name kept files the
     * live database does not (yet) know, and deleting those would leave the restored rows pointing at nothing.
     */
    override fun sweepMedia(keep: Set<String>) {
        runCatching {
            if (DatabaseBackups.restorableBackups(appContext, SpamDatabase.DATABASE_NAME, Int.MAX_VALUE).isNotEmpty()) {
                return
            }
            BlockedMmsStore.sweep(appContext, keep)
        }
    }
}
