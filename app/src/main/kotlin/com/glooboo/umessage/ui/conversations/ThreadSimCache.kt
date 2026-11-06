package com.glooboo.umessage.ui.conversations

import android.content.Context
import android.provider.Telephony.Sms
import android.util.Log
import java.io.File

/**
 * The SIM of each thread's newest SMS, for the SIM chips in the chat list.
 *
 * MC-18: this used to read every SMS on the phone each time the list opened. Now nothing is read with fewer than two
 * SIMs (no chips are shown), and otherwise only the messages newer than the last read: the map and the date it is
 * complete up to are kept in a small file, so a restart reads just what arrived meanwhile.
 */
class ThreadSimCache(private val file: File) {

    private var loaded = false
    private var newestMs = 0L
    private val sims = HashMap<Long, Int>()

    /** Thread id to subscription id; empty without a second SIM. Call off the main thread. */
    @Synchronized
    fun load(context: Context, simCount: Int): Map<Long, Int> {
        if (simCount < 2) return emptyMap()
        if (!loaded) {
            readFile()
            loaded = true
        }
        val fresh = HashMap<Long, Int>()
        var newest = newestMs
        try {
            // ">=" so a message stored in the same millisecond as the last one read is not missed.
            context.contentResolver.query(
                Sms.CONTENT_URI,
                arrayOf(Sms.THREAD_ID, Sms.SUBSCRIPTION_ID, Sms.DATE),
                "${Sms.SUBSCRIPTION_ID} > 0 AND ${Sms.DATE} >= ?",
                arrayOf(newestMs.toString()),
                "${Sms.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val thread = cursor.getLong(0)
                    if (!fresh.containsKey(thread)) fresh[thread] = cursor.getInt(1)
                    newest = maxOf(newest, cursor.getLong(2))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read message SIMs", e)
            return HashMap(sims)
        }
        val changed = newest != newestMs || fresh.any { (thread, sub) -> sims[thread] != sub }
        if (changed) {
            sims.putAll(fresh)
            newestMs = newest
            writeFile()
        }
        return HashMap(sims)
    }

    private fun readFile() {
        try {
            if (!file.exists()) return
            val lines = file.readLines()
            val date = lines.firstOrNull()?.toLongOrNull() ?: return
            val read = HashMap<Long, Int>()
            lines.drop(1).forEach { line ->
                val thread = line.substringBefore(' ').toLongOrNull() ?: return
                val sub = line.substringAfter(' ').toIntOrNull() ?: return
                read[thread] = sub
            }
            sims.putAll(read)
            newestMs = date
        } catch (e: Exception) {
            // A damaged file only means one full read.
            sims.clear()
            newestMs = 0L
        }
    }

    private fun writeFile() {
        try {
            val text = buildString {
                append(newestMs).append('\n')
                sims.forEach { (thread, sub) -> append(thread).append(' ').append(sub).append('\n') }
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save message SIMs", e)
        }
    }

    companion object {
        private const val TAG = "ThreadSimCache"
        private const val FILE_NAME = "thread_sims.txt"

        @Volatile private var shared: ThreadSimCache? = null

        fun get(context: Context): ThreadSimCache = shared ?: synchronized(this) {
            shared ?: ThreadSimCache(File(context.applicationContext.noBackupFilesDir, FILE_NAME)).also { shared = it }
        }
    }
}
