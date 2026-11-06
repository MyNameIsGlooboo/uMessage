package com.glooboo.umessage.messaging

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.webkit.MimeTypeMap
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A scheduled MMS goes out hours or days after the photo was picked. By then the picker's read permission has
 * usually expired, or the file was deleted or cleared from a cache, so the app keeps its own copy of every
 * attachment in private storage (files/scheduled_attachments/<message id>/) and the message points at that copy.
 * The copy is deleted once the message was sent or the schedule removed.
 */

private const val SCHEDULED_ATTACHMENTS_DIR = "scheduled_attachments"

private fun Context.scheduledAttachmentsRoot() = File(filesDir, SCHEDULED_ATTACHMENTS_DIR)

private fun Context.scheduledAttachmentsDir(messageId: Long) = File(scheduledAttachmentsRoot(), messageId.toString())

private fun Context.fileProviderAuthority() = "$packageName.provider"

/**
 * The app's FileProvider uri for a kept copy: the `scheduled_attachments` root of provider_paths.xml, then the
 * path under it. Built here rather than with FileProvider.getUriForFile, which gives the same uri but cannot match
 * paths on a Windows test machine.
 */
private fun Context.keptUriOf(messageId: Long, fileName: String): Uri = Uri.Builder()
    .scheme("content")
    .authority(fileProviderAuthority())
    .appendPath(SCHEDULED_ATTACHMENTS_DIR)
    .appendPath(messageId.toString())
    .appendPath(fileName)
    .build()

/** The private copy [uri] points at when it is one of [messageId]'s kept attachments, else null. */
private fun Context.keptFileOf(uri: Uri, messageId: Long): File? {
    if (uri.scheme != "content" || uri.authority != fileProviderAuthority()) return null
    val segments = uri.pathSegments
    if (segments.size != 3 || segments[0] != SCHEDULED_ATTACHMENTS_DIR || segments[1] != messageId.toString()) {
        return null
    }
    return File(scheduledAttachmentsDir(messageId), segments[2])
}

/** Whether [message] has an attachment that is not one of its own private copies. */
fun Context.hasExternalScheduledAttachments(message: Message): Boolean =
    message.attachment?.attachments.orEmpty().any { keptFileOf(it.getUri(), message.id) == null }

/**
 * Returns [message] with every attachment copied into private storage. Attachments that already are its own
 * copies (a scheduled message being edited) stay as they are; copies no longer used are deleted. Throws when an
 * attachment cannot be read, so the caller can tell the user instead of scheduling a message that will fail.
 */
@Throws(IOException::class)
fun Context.keepScheduledAttachments(message: Message): Message {
    val messageAttachment = message.attachment
    val attachments = messageAttachment?.attachments.orEmpty()
    val dir = scheduledAttachmentsDir(message.id)
    if (attachments.isEmpty()) {
        dir.deleteRecursively()
        return message
    }

    dir.mkdirs()
    val created = ArrayList<File>()
    val kept = try {
        attachments.map { attachment ->
            if (keptFileOf(attachment.getUri(), message.id)?.exists() == true) {
                attachment
            } else {
                val file = File(dir, newFileName(attachment))
                created += file
                copyInto(attachment.getUri(), file)
                attachment.copy(uriString = keptUriOf(message.id, file.name).toString(), messageId = message.id)
            }
        }
    } catch (e: Exception) {
        created.forEach { it.delete() }
        throw e as? IOException ?: IOException(e)
    }

    val keptNames = kept.mapNotNull { keptFileOf(it.getUri(), message.id)?.name }.toSet()
    dir.listFiles()?.filter { it.name !in keptNames }?.forEach { it.deleteRecursively() }
    return message.copy(attachment = messageAttachment!!.copy(attachments = ArrayList(kept)))
}

private fun newFileName(attachment: Attachment): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mimetype)
    val base = UUID.randomUUID().toString()
    return if (extension.isNullOrEmpty()) base else "$base.$extension"
}

private fun Context.copyInto(uri: Uri, file: File) {
    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
    input.use { source -> file.outputStream().use { source.copyTo(it) } }
}

/** Whether every attachment of [message] can still be opened. */
fun Context.canReadScheduledAttachments(message: Message): Boolean =
    message.attachment?.attachments.orEmpty().all { attachment ->
        try {
            contentResolver.openInputStream(attachment.getUri())?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

/** Deletes the private copies kept for scheduled message [messageId]. */
fun Context.deleteScheduledAttachments(messageId: Long) {
    runCatching { scheduledAttachmentsDir(messageId).deleteRecursively() }
}

/**
 * Runs on app start. Scheduled messages saved before attachments were copied still point at the picker's uri:
 * each pending one is copied now while the uri may still be readable (one that is not stays as it is, and its send
 * is held with a notification when its time comes). Copies left by messages that no longer exist are deleted.
 */
fun Context.tidyScheduledAttachments() {
    val scheduled = try {
        messagesDB.getAllScheduledMessages()
    } catch (_: Exception) {
        return
    }

    scheduled
        .filter { it.type != Telephony.Sms.MESSAGE_TYPE_FAILED && hasExternalScheduledAttachments(it) }
        .forEach { message ->
            runCatching { messagesDB.insertOrUpdate(keepScheduledAttachments(message)) }
        }

    val ids = scheduled.map { it.id.toString() }.toSet()
    scheduledAttachmentsRoot().listFiles()?.filter { it.name !in ids }?.forEach { it.deleteRecursively() }
}
