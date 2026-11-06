package com.glooboo.umessage.spam.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/**
 * The downloaded content of blocked MMS, kept so "Not spam" can restore it with its pictures (SE-29).
 *
 * Files live in the app's no-backup directory (app-private, never in a backup or device transfer, never on external
 * storage, not reachable through any FileProvider). Each kept MMS is `<name>.pdu` (the whole downloaded m-retrieve-conf,
 * exactly as the carrier sent it) plus an optional small `<name>.jpg` thumbnail for the Blocked screen. The name is
 * what the blocked_messages row stores in `mediaFile`.
 */
object BlockedMmsStore {
    private const val DIR = "blocked_mms"
    private const val PDU = ".pdu"
    private const val THUMB = ".jpg"
    private const val THUMB_SIZE_PX = 256
    private const val THUMB_QUALITY = 80

    /** Files younger than this are never swept: their row may not be written yet. */
    const val SWEEP_MIN_AGE_MS = 60 * 60 * 1000L

    private val NAME = Regex("[0-9a-f-]{8,64}")

    fun dir(context: Context): File = File(context.noBackupFilesDir, DIR)

    /** The kept PDU of [name], or null for a name this store never produced. */
    fun pduFile(context: Context, name: String): File? = file(context, name, PDU)

    fun thumbnailFile(context: Context, name: String): File? = file(context, name, THUMB)

    private fun file(context: Context, name: String, suffix: String): File? =
        if (NAME.matches(name)) File(dir(context), name + suffix) else null

    /**
     * Keeps [pdu] and writes a thumbnail of its first picture. Returns the name to store in the row, or null when
     * [pdu] is not a downloaded MMS or it could not be written (then nothing is left behind). Never throws.
     */
    fun save(context: Context, pdu: ByteArray, content: BlockedMmsContent? = BlockedMmsContent.parse(pdu)): String? {
        if (content == null) return null
        val name = UUID.randomUUID().toString()
        return try {
            val dir = dir(context).apply { mkdirs() }
            val target = File(dir, name + PDU)
            val partial = File(dir, "$name$PDU.part")
            partial.writeBytes(pdu)
            if (!partial.renameTo(target)) {
                partial.delete()
                return null
            }
            content.firstImage?.let { writeThumbnail(it, File(dir, name + THUMB)) }
            name
        } catch (_: Throwable) {
            delete(context, listOf(name))
            null
        }
    }

    fun delete(context: Context, names: Collection<String>) {
        names.forEach { name ->
            pduFile(context, name)?.delete()
            thumbnailFile(context, name)?.delete()
        }
    }

    /** Deletes files no row refers to any more (a crash between writing a file and its row), if old enough. */
    fun sweep(context: Context, keep: Set<String>, now: Long = System.currentTimeMillis()) {
        val files = dir(context).listFiles() ?: return
        files.forEach { file ->
            val name = file.name.substringBefore('.')
            if (name !in keep && now - file.lastModified() > SWEEP_MIN_AGE_MS) file.delete()
        }
    }

    private fun writeThumbnail(image: ByteArray, target: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_SIZE_PX && bounds.outHeight / (sample * 2) >= THUMB_SIZE_PX) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return
            try {
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
            target.delete()
        }
    }
}
