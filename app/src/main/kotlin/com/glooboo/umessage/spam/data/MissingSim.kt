package com.glooboo.umessage.spam.data

import android.content.ContentUris
import android.content.Context
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.subscriptionManagerCompat

/**
 * J18: a held message may name a SIM that is not in the phone any more. Newer Android takes such an insert and then
 * hides the row from every query, which would hide a message the user asked for. A row is therefore written without
 * the SIM when the SIM is not active, and one that was written with a SIM but cannot be read back is deleted and
 * written again without it. Shared by "Not spam" (SMS and MMS) and the older-spam migration.
 */
internal object MissingSim {
    /** The store's default: no SIM. */
    const val NONE = -1

    /** Whether [subscriptionId] is an active SIM now; null when that cannot be told (then it is tried as is). */
    fun active(context: Context, subscriptionId: Int): Boolean? = runCatching {
        context.subscriptionManagerCompat().activeSubscriptionInfoList?.any { it.subscriptionId == subscriptionId }
    }.getOrNull()

    /** Whether store row [id] can be read back. Throws when the store cannot be asked. */
    fun readable(context: Context, id: Long, isMms: Boolean): Boolean =
        context.contentResolver.query(
            ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id),
            arrayOf(BaseColumns._ID), null, null, null,
        )?.use { it.count > 0 } ?: true

    /** Deletes store row [id] and its copy in the app's message cache; true when the store row is gone. */
    fun delete(context: Context, id: Long, isMms: Boolean): Boolean {
        val gone = context.contentResolver
            .delete(ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id), null, null) == 1
        if (gone) runCatching { context.messagesDB.delete(id, isMms) }
        return gone
    }

    /**
     * [write] puts the message in the store on the SIM it is given and returns the new id (0 or less: nothing written).
     * The SIM is dropped first when it is not active; a row written with a SIM that cannot be read back is deleted by
     * its id and written again without one. Returns the id of the row that is finally there. Throws when a hidden row
     * could not be deleted, or the row is hidden even without a SIM (that one is deleted too): none is left behind.
     */
    fun writeReadably(context: Context, subscriptionId: Int, isMms: Boolean, write: (Int) -> Long): Long =
        writeReadably(
            subscriptionId,
            { active(context, it) },
            write,
            { runCatching { readable(context, it, isMms) }.getOrDefault(true) },
            { delete(context, it, isMms) },
        )

    /** The steps of [writeReadably] with the store behind lambdas. */
    fun writeReadably(
        subscriptionId: Int,
        active: (Int) -> Boolean?,
        write: (Int) -> Long,
        readable: (Long) -> Boolean,
        delete: (Long) -> Boolean,
    ): Long {
        val sim = if (subscriptionId < 0 || runCatching { active(subscriptionId) }.getOrNull() == false) {
            NONE
        } else {
            subscriptionId
        }
        val id = write(sim)
        if (id <= 0L || sim == NONE || readable(id)) return id
        check(delete(id)) { "A row the store hides could not be deleted" }
        val again = write(NONE)
        if (again > 0L && !readable(again)) {
            delete(again)
            error("The store hides the row even without a SIM")
        }
        return again
    }
}
