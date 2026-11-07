package com.glooboo.umessage.backup

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Writes a backup so that the file the user picked is never left empty or half written. The data is produced into a
 * temporary file in the app's cache first; only when that succeeded and is not empty is it copied into the chosen
 * location, and the copy is checked. Opening the destination with a truncating mode before the data exists (what the
 * backups used to do) leaves a 0-byte file whenever the work then fails, is stopped, finds nothing, or the process
 * dies; here the destination is not touched until there is something to put in it.
 */
object SafeOutput {

    /** [value] is what the producer returned; [copied] says whether it was copied to the destination. */
    class Result<T>(val value: T, val copied: Boolean, val bytes: Long)

    /**
     * Runs [produce] on a temporary file, then copies it to [uri] when [keep] accepts the produced value and the file
     * is not empty. Throws [IOException] when the file is empty although [keep] accepted it, when the destination
     * cannot be opened or written, or when the copy is shorter than the source. Streams are always closed and the
     * temporary file is always removed.
     */
    fun <T> write(
        context: Context,
        uri: Uri,
        keep: (T) -> Boolean = { true },
        produce: (OutputStream) -> T,
    ): Result<T> {
        val temp = File.createTempFile("backup", ".tmp", context.cacheDir)
        try {
            val value = temp.outputStream().buffered().use { stream ->
                produce(stream).also { stream.flush() }
            }
            val size = temp.length()
            if (!keep(value)) return Result(value, copied = false, bytes = size)
            if (size == 0L) throw IOException("The backup came out empty")
            copy(context, temp, uri, size)
            return Result(value, copied = true, bytes = size)
        } finally {
            temp.delete()
        }
    }

    /**
     * The same safety for a backup that stays a file of its own (the one handed to Google Drive): produced into
     * "[destination].part" next to it and renamed to [destination] only when [keep] accepted the result and the file is
     * not empty, so there is a single file, never an empty or half written one. Throws [IOException] otherwise.
     */
    fun <T> writeFile(destination: File, keep: (T) -> Boolean = { true }, produce: (OutputStream) -> T): Result<T> {
        val part = File(destination.parentFile, destination.name + ".part")
        try {
            val value = part.outputStream().buffered().use { stream ->
                produce(stream).also { stream.flush() }
            }
            val size = part.length()
            if (!keep(value)) return Result(value, copied = false, bytes = size)
            if (size == 0L) throw IOException("The backup came out empty")
            destination.delete()
            if (!part.renameTo(destination)) throw IOException("Cannot save the file")
            return Result(value, copied = true, bytes = size)
        } finally {
            part.delete()
        }
    }

    private fun copy(context: Context, source: File, uri: Uri, size: Long) {
        val resolver = context.contentResolver
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the file")
            out.use { stream ->
                source.inputStream().use { it.copyTo(stream) }
                stream.flush()
            }
        } catch (e: IOException) {
            throw IOException("Cannot write the file: ${e.message}", e)
        }
        // Where the provider can say how long the file now is, it must be as long as what was written.
        val written = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (_: Exception) {
            null
        }
        if (written != null && written >= 0 && written != size) {
            lengthMismatch(written, size)
        }
    }

    private fun lengthMismatch(written: Long, size: Long): Nothing =
        throw IOException("The file has $written bytes instead of $size")
}
