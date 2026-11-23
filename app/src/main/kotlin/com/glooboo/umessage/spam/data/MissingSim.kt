package com.glooboo.umessage.spam.data

import android.content.ContentUris
import android.content.ContentValues
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Log
import androidx.core.content.ContextCompat
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.extensions.updateLastConversationMessage

/**
 * J18: a held message may name a SIM that is not in the phone any more. Android 14's store takes such an insert and
 * then hides the row from every query. A visible message always wins, so (probed on Android 14):
 *
 * - The SIM is dropped before the write unless it is known to be active now. A list that cannot be read, or is empty
 *   (Android 14 without the Phone permission), counts as "not known": the SIM is dropped. The SIM label of such a
 *   message is lost on every Android version, not only where the store hides rows; the message itself is kept.
 * - A write that is refused (or throws) with a SIM is tried once more without one.
 * - A row written with a SIM that cannot be read back is put right: a hidden SMS is deleted by its id URI (a delete
 *   with a where clause, and an update, do not reach it) and written again without a SIM; a hidden MMS cannot be
 *   deleted, but an update of its `sub_id` to none by its id URI makes the same row visible, with its parts and
 *   addresses, so no second row is written. Only when that fails too is a visible copy written without a SIM (a hidden
 *   copy may then show up as a double if a SIM with that id comes back) and one line with the hidden id is logged.
 * - An SMS id is reused after a delete: the id of a deleted hidden row is never used again by the callers.
 * - A read-back that cannot be answered at all (error, no cursor) fails the write: the row just written is deleted
 *   on a best-effort basis and the caller keeps its held copy, so no second visible copy is made.
 *
 * Shared by "Not spam" (SMS and MMS), the replay of a queued "Not spam" and the older-spam migration.
 */
internal object MissingSim {
    private const val TAG = "uMessageReceive"

    /** The store's default: no SIM. */
    const val NONE = -1

    /**
     * The store behind [writeReadably]. [active]: whether a SIM is active now (null: cannot be told). [readable]:
     * whether a written row can be read back (null: cannot be asked). [makeVisible]: the in-place fix of a hidden row
     * (an MMS: its SIM set to none); true when the row can be read now. [delete]: deletes a row by its id. [refresh]:
     * dates the chat of the final row again.
     */
    class Ops(
        val active: (Int) -> Boolean? = { true },
        val readable: (Long) -> Boolean? = { true },
        val makeVisible: (Long) -> Boolean = { false },
        val delete: (Long) -> Boolean = { true },
        val refresh: (Long) -> Unit = {},
        /** A first write that threw on a SIM is tried again without one (not for an MMS: it may leave a half row). */
        val retryAfterError: Boolean = true,
    )

    /** Whether [subscriptionId] is an active SIM now; null when that cannot be told. */
    fun active(context: Context, subscriptionId: Int): Boolean? {
        val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!canRead) return null
        return runCatching {
            context.subscriptionManagerCompat().activeSubscriptionInfoList?.any { it.subscriptionId == subscriptionId }
        }.getOrNull()
    }

    /** The SIM a held row is written on: its own only when it is known to be active, else [NONE]. */
    fun simFor(context: Context, subscriptionId: Int): Int = simFor(subscriptionId) { active(context, it) }

    private fun simFor(subscriptionId: Int, active: (Int) -> Boolean?): Int =
        if (subscriptionId >= 0 && runCatching { active(subscriptionId) }.getOrNull() == true) subscriptionId else NONE

    /** Whether store row [id] can be read back; null when the store cannot be asked (error, no cursor). */
    fun readable(context: Context, id: Long, isMms: Boolean): Boolean? =
        context.contentResolver.query(storeUri(id, isMms), arrayOf(BaseColumns._ID), null, null, null)
            ?.use { it.count > 0 }

    /**
     * Sets the SIM of MMS [id] to none by its id URI (the one fix a hidden MMS accepts); true when the row can be read
     * back afterwards.
     */
    fun dropMmsSim(context: Context, id: Long): Boolean {
        val values = ContentValues().apply { put(Mms.SUBSCRIPTION_ID, NONE) }
        val updated = runCatching { context.contentResolver.update(storeUri(id, true), values, null, null) == 1 }
        return updated.getOrDefault(false) && runCatching { readable(context, id, true) }.getOrNull() == true
    }

    /**
     * Deletes store row [id] by its id URI and its copy in the app's message cache (also when the store delete was
     * refused: the cache must not show a row the store hides), then dates the chat by what is left. True when the store
     * row is gone.
     */
    fun delete(context: Context, id: Long, isMms: Boolean): Boolean {
        val threadId = runCatching { context.messagesDB.getMessage(id, isMms)?.threadId }.getOrNull()
        val uri = storeUri(id, isMms)
        val gone = runCatching { context.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false)
        runCatching { context.messagesDB.delete(id, isMms) }
        threadId?.let { runCatching { context.updateLastConversationMessage(it) } }
        return gone
    }

    /**
     * [write] puts the message in the store on the SIM it is given and returns the new id (0 or less: nothing
     * written). Returns the id of the row that is finally there. Throws when nothing could be written, when the row
     * cannot be read back at all, or when it is hidden even without a SIM (then it is deleted): none is left behind.
     */
    fun writeReadably(context: Context, subscriptionId: Int, isMms: Boolean, write: (Int) -> Long): Long =
        writeReadably(
            subscriptionId,
            Ops(
                active = { active(context, it) },
                readable = { runCatching { readable(context, it, isMms) }.getOrNull() },
                makeVisible = { isMms && dropMmsSim(context, it) },
                delete = { delete(context, it, isMms) },
                retryAfterError = !isMms,
                refresh = { id ->
                    // A hidden row counted in the chat meanwhile: the chat is dated by its newest message again.
                    runCatching {
                        context.contentResolver.query(storeUri(id, isMms), arrayOf(Sms.THREAD_ID), null, null, null)
                            ?.use { if (it.moveToFirst()) context.updateLastConversationMessage(it.getLong(0)) }
                    }
                },
            ),
            write,
        )

    /** The steps of [writeReadably] with the store behind [ops]. */
    fun writeReadably(subscriptionId: Int, ops: Ops, write: (Int) -> Long): Long {
        val sim = simFor(subscriptionId, ops.active)
        val (id, usedSim) = writeOnce(sim, write, ops.retryAfterError)
        if (id <= 0L || usedSim == NONE) return id
        return when (ops.readable(id)) {
            true -> id
            null -> {
                runCatching { ops.delete(id) }
                error("The store could not be asked about the row just written")
            }
            false -> if (runCatching { ops.makeVisible(id) }.getOrDefault(false)) {
                id.also(ops.refresh)
            } else {
                writeAgainWithoutSim(id, write, ops)
            }
        }
    }

    /** The first write: a refused one (0, or an error) on a SIM is tried once without it. Its id and its SIM. */
    private fun writeOnce(sim: Int, write: (Int) -> Long, retryAfterError: Boolean): Pair<Long, Int> {
        if (sim == NONE) return write(NONE) to NONE
        val id = if (retryAfterError) runCatching { write(sim) }.getOrDefault(0L) else write(sim)
        return if (id == 0L) write(NONE) to NONE else id to sim
    }

    /** The hidden row [hidden] is deleted (its id is not used again) and the message written without a SIM. */
    private fun writeAgainWithoutSim(hidden: Long, write: (Int) -> Long, ops: Ops): Long {
        if (!runCatching { ops.delete(hidden) }.getOrDefault(false)) {
            Log.w(TAG, "A row the store hides could not be deleted (id $hidden)")
        }
        val again = write(NONE)
        if (again > 0L && ops.readable(again) == false) {
            runCatching { ops.delete(again) }
            error("The store hides the row even without a SIM")
        }
        return again.also { if (it > 0L) ops.refresh(it) }
    }
}

private fun storeUri(id: Long, isMms: Boolean) =
    ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id)
