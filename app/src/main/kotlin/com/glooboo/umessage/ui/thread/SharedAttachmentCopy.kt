package com.glooboo.umessage.ui.thread

import android.content.Context
import android.net.Uri
import android.util.Log
import com.glooboo.umessage.extensions.getFileSizeFromUri
import com.glooboo.umessage.extensions.isGifMimeType
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import org.fossify.commons.extensions.getFilenameFromUri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The most a shared file may take in the private cache, whatever the MMS limit says (unknown sizes included). */
const val MAX_SHARED_COPY_BYTES = 100L * 1024 * 1024

private const val TAG = "SharedAttachment"
private const val MAX_EXTENSION_LENGTH = 8
private const val BUFFER_SIZE = 64 * 1024

/** The shared stream is longer than the allowed size. */
class SharedFileTooLargeException : IOException()

/** How copying a shared stream ended. */
sealed interface SharedCopy {
    data class Copied(val file: File) : SharedCopy
    data object TooLarge : SharedCopy
    data object Failed : SharedCopy
}

/** What to do with a shared file after it was checked and (when it passed) copied. */
sealed interface SharedPrepared {
    /** The file cannot be attached ([check] says why). */
    data class Rejected(val check: AttachmentCheck) : SharedPrepared

    /** The private [file] is what gets attached, with the original [mimeType] and [filename]. */
    data class Copied(val file: File, val mimeType: String, val filename: String) : SharedPrepared

    /** The copy could not be made: the shared uri is attached as it is. */
    data object Unreadable : SharedPrepared
}

/** The most bytes to copy of a shared file: the MMS limit when it applies, never more than [MAX_SHARED_COPY_BYTES]. */
fun sharedCopyLimit(sizeLimit: Long, limited: Boolean): Long =
    if (limited && sizeLimit != FILE_SIZE_NONE) minOf(sizeLimit, MAX_SHARED_COPY_BYTES) else MAX_SHARED_COPY_BYTES

/**
 * Copies [uri] into [dir]; the copy's name keeps only the extension of [filename]. Over [maxBytes] it stops with
 * [SharedCopy.TooLarge]; that and any other failure delete the partial file.
 */
fun copySharedStream(context: Context, uri: Uri, filename: String, dir: File, maxBytes: Long): SharedCopy {
    val extension = filename.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(MAX_EXTENSION_LENGTH)
    var file: File? = null
    return runCatching {
        // The temp file is made inside runCatching: a full cache or a missing folder ends as a failed copy.
        val target = File.createTempFile("shared_", if (extension.isEmpty()) ".bin" else ".$extension", dir)
        file = target
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream")
        input.use { source -> target.outputStream().use { out -> copyLimited(source, out, maxBytes) } }
        target
    }.fold(
        onSuccess = { SharedCopy.Copied(it) },
        onFailure = {
            file?.delete()
            if (it is SharedFileTooLargeException) {
                SharedCopy.TooLarge
            } else {
                // Only the kind of failure is logged, never the uri or the name.
                Log.w(TAG, "Shared file not copied: ${it.javaClass.simpleName}")
                SharedCopy.Failed
            }
        },
    )
}

private fun copyLimited(source: InputStream, out: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) return
        total += read
        if (total > maxBytes) throw SharedFileTooLargeException()
        out.write(buffer, 0, read)
    }
}

/**
 * Checks a shared file (type, size, [alreadyAdded]) and copies it into [dir]. Runs on a worker thread. Images over
 * [sizeLimit] are copied too (they are shrunk later); the hard cap [MAX_SHARED_COPY_BYTES] holds for all.
 */
fun prepareShared(context: Context, uri: Uri, sizeLimit: Long, alreadyAdded: Boolean, dir: File): SharedPrepared {
    val check = checkAttachment(
        alreadyAdded = alreadyAdded,
        mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
        sizeLimit = sizeLimit,
        fileSize = { runCatching { context.getFileSizeFromUri(uri) }.getOrDefault(FILE_SIZE_NONE) },
    )
    if (check !is AttachmentCheck.Ok) return SharedPrepared.Rejected(check)
    val filename = runCatching { context.getFilenameFromUri(uri) }.getOrDefault("")
    val maxBytes = sharedCopyLimit(sizeLimit, limited = !check.compress || check.mimeType.isGifMimeType())
    return when (val copy = copySharedStream(context, uri, filename, dir, maxBytes)) {
        is SharedCopy.Copied -> SharedPrepared.Copied(copy.file, check.mimeType, filename)
        SharedCopy.TooLarge -> SharedPrepared.Rejected(AttachmentCheck.TooLarge)
        SharedCopy.Failed -> SharedPrepared.Unreadable
    }
}
