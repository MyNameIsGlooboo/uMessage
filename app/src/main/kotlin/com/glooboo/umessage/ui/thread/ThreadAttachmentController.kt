package com.glooboo.umessage.ui.thread

import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.copyToUri
import com.glooboo.umessage.extensions.getFileSizeFromUri
import com.glooboo.umessage.extensions.isGifMimeType
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.helpers.AudioPlayerManager
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.helpers.ImageCompressor
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.AttachmentSelection
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getFilenameFromUri
import org.fossify.commons.extensions.getMyFileUri
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.VcfExporter
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

/** Whether a picked file can be attached. */
sealed interface AttachmentCheck {
    /** [compress]: a still image is shrunk to the MMS size limit before it can be sent. */
    data class Ok(val mimeType: String, val compress: Boolean) : AttachmentCheck
    data object Duplicate : AttachmentCheck
    data object UnknownType : AttachmentCheck
    data object TooLarge : AttachmentCheck
}

/**
 * [alreadyAdded]: the same uri is in the composer. Images other than GIFs are compressed, so only other files are
 * held to [sizeLimit] ([FILE_SIZE_NONE] for no limit); [fileSize] is read only then.
 */
fun checkAttachment(alreadyAdded: Boolean, mimeType: String?, sizeLimit: Long, fileSize: () -> Long): AttachmentCheck {
    if (alreadyAdded) return AttachmentCheck.Duplicate
    if (mimeType == null) return AttachmentCheck.UnknownType
    val isImage = mimeType.isImageMimeType()
    val isGif = mimeType.isGifMimeType()
    if ((isGif || !isImage) && sizeLimit != FILE_SIZE_NONE && fileSize() > sizeLimit) return AttachmentCheck.TooLarge
    return AttachmentCheck.Ok(mimeType, compress = isImage && !isGif)
}

/** True when files of [sizes] together exceed [limit] ([FILE_SIZE_NONE] means no limit): the carrier counts the sum. */
fun totalTooLarge(sizes: Collection<Long>, limit: Long): Boolean =
    limit != FILE_SIZE_NONE && sizes.size > 1 && sizes.sum() > limit

/**
 * The composer's attachments: adding a picked file (with the size checks and image compression), a contact as a
 * vCard, removing one, and saving an MMS attachment where the user chose. The picked files live in [vm], so they
 * survive rotation; [onChanged] runs whenever the list changed (the message may switch between SMS and MMS).
 */
class ThreadAttachmentController(
    private val activity: BaseSimpleActivity,
    private val vm: ThreadViewModel,
    private val onChanged: () -> Unit,
) {
    private val imageCompressor by lazy { ImageCompressor(activity) }

    private val attachmentsDir: File
        get() = File(activity.cacheDir, "attachments").apply { if (!exists()) mkdirs() }

    /** The size of each added file that is sent as it is (not shrunk), by attachment id: the carrier counts the sum. */
    private val fileSizes = HashMap<String, Long>()

    /** The private copy of each shared file, by the uri it was shared as; a second share of it is a duplicate. */
    private val sharedCopies = HashMap<String, String>()

    /**
     * Adds a picked file. The type, size and name are read on a worker thread (a slow provider no longer stalls the
     * screen, I4); the composer changes back on the main thread. A [shared] file (from another app) has its read right
     * end soon, so it is copied into the private cache first and the copy is attached; a failed copy attaches the
     * shared uri as it is.
     */
    fun add(uri: Uri, knownMimeType: String? = null, knownFilename: String? = null, shared: Boolean = false) {
        val id = uri.toString()
        // The same shared file again: its copy is still in the composer.
        val alreadyAdded = vm.hasAttachment(id) || (shared && sharedCopies[id]?.let { vm.hasAttachment(it) } == true)
        val sizeLimit = activity.config.mmsFileSizeLimit
        ensureBackgroundThread {
            if (shared) {
                when (val prepared = prepareShared(activity, uri, sizeLimit, alreadyAdded, attachmentsDir)) {
                    is SharedPrepared.Rejected ->
                        activity.runOnUiThread { applyCheck(uri, id, prepared.check, "", FILE_SIZE_NONE) }
                    is SharedPrepared.Copied -> {
                        val copyUri = activity.getMyFileUri(prepared.file)
                        activity.runOnUiThread {
                            sharedCopies[id] = copyUri.toString()
                            add(copyUri, prepared.mimeType, prepared.filename)
                        }
                    }
                    SharedPrepared.Unreadable -> activity.runOnUiThread { add(uri) }
                }
                return@ensureBackgroundThread
            }
            var size = FILE_SIZE_NONE
            val check = checkAttachment(
                alreadyAdded = alreadyAdded,
                mimeType = knownMimeType ?: activity.contentResolver.getType(uri),
                sizeLimit = sizeLimit,
                fileSize = { activity.getFileSizeFromUri(uri).also { size = it } },
            )
            val filename = if (check is AttachmentCheck.Ok) knownFilename ?: activity.getFilenameFromUri(uri) else ""
            // Only a file that is not shrunk keeps its size; the sum is read from there (see [totalTooLarge]).
            val sentAsIs = (check as? AttachmentCheck.Ok)?.takeIf { !it.compress }
            if (sentAsIs != null && size == FILE_SIZE_NONE) size = activity.getFileSizeFromUri(uri)
            activity.runOnUiThread { applyCheck(uri, id, check, filename, size) }
        }
    }

    private fun applyCheck(uri: Uri, id: String, check: AttachmentCheck, filename: String, size: Long) {
        when (check) {
            AttachmentCheck.Duplicate -> activity.toast(R.string.duplicate_item_warning)
            AttachmentCheck.UnknownType -> activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
            AttachmentCheck.TooLarge -> activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
            is AttachmentCheck.Ok -> {
                val attachment = AttachmentSelection(
                    id = id,
                    uri = uri,
                    mimetype = check.mimeType,
                    filename = filename,
                    isPending = check.compress,
                )
                vm.addAttachment(attachment)
                if (!check.compress && size > 0L) fileSizes[id] = size
                if (attachment.isPending) compress(attachment)
                onChanged()
                // Each file may pass on its own while all of them together are too big for one message (I4).
                val limit = activity.config.mmsFileSizeLimit
                if (totalTooLarge(fileSizes.filterKeys { key -> vm.hasAttachment(key) }.values, limit)) {
                    activity.toast(R.string.attachments_total_too_large, length = Toast.LENGTH_LONG)
                }
            }
        }
    }

    /**
     * Shrinks a freshly picked image to the MMS size limit and, unless switched off, drops its location; sending stays
     * disabled until it is ready.
     */
    private fun compress(attachment: AttachmentSelection) {
        // No limit still goes through the compressor: it only copies the picture without its location (I09).
        val limit = activity.config.mmsFileSizeLimit.takeIf { it != FILE_SIZE_NONE } ?: Long.MAX_VALUE
        imageCompressor.compressImage(attachment.uri, limit) { compressedUri ->
            activity.runOnUiThread {
                when (compressedUri) {
                    attachment.uri -> vm.replaceAttachment(attachment.id, attachment.copy(isPending = false))
                    null -> {
                        activity.toast(R.string.compress_error)
                        vm.removeAttachment(attachment.id)
                    }

                    else -> vm.replaceAttachment(attachment.id, attachment.copy(uri = compressedUri, isPending = false))
                }
                onChanged()
            }
        }
    }

    fun remove(attachment: AttachmentSelection) {
        fileSizes.remove(attachment.id)
        AudioPlayerManager.release(attachment.uri)
        vm.removeAttachment(attachment.id)
        onChanged()
    }

    /**
     * After a send that went out in part: drops the attachments that were handed off and keeps the ones in
     * [remaining], so another tap on Send resends only those.
     */
    fun keepOnly(remaining: List<Attachment>) {
        val keep = remaining.map { it.uriString }.toSet()
        vm.attachments.filter { it.uri.toString() !in keep }.forEach { remove(it) }
    }

    /** Empties the composer's attachments (after a send), stopping any that was playing. */
    fun clear() {
        fileSizes.clear()
        vm.attachments.forEach { AudioPlayerManager.release(it.uri) }
        vm.clearAttachments()
    }

    /** The composer's attachments as message parts of [messageId] (-1 for a message sent now). */
    fun asMessageAttachments(messageId: Long = -1L): ArrayList<Attachment> =
        vm.attachments.mapTo(ArrayList()) { Attachment(null, messageId, it.uri.toString(), it.mimetype, 0, 0, it.filename) }

    /** A new empty file for the camera to write into, as a uri the camera app may write. */
    fun newCaptureUri(): Uri = activity.getMyFileUri(File.createTempFile("attachment_", ".jpg", attachmentsDir))

    /** Attaches the picked contact as a vCard file. */
    fun addContact(contactUri: Uri) {
        ensureBackgroundThread {
            val contact = ContactsHelper(activity).getContactFromUri(contactUri)
            if (contact == null) {
                activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@ensureBackgroundThread
            }
            val outputFile = File(attachmentsDir, "${contact.contactId}.vcf")
            VcfExporter().exportContacts(
                activity = activity,
                outputStream = outputFile.outputStream(),
                contacts = arrayListOf(contact),
                showExportingToast = false,
            ) {
                if (it == ExportResult.EXPORT_OK) {
                    val vCardUri = activity.getMyFileUri(outputFile)
                    activity.runOnUiThread { add(vCardUri) }
                } else {
                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        }
    }

    /**
     * Copies [attachments] to [destinationUri]: a document for one attachment, a folder for several. Runs in the
     * background and says how it went.
     */
    fun save(attachments: List<Attachment>?, destinationUri: Uri) {
        if (attachments.isNullOrEmpty()) {
            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
            return
        }
        ensureBackgroundThread {
            try {
                if (DocumentsContract.isTreeUri(destinationUri)) {
                    // Only needed for folders, and not every provider offers it: never fail the save over it.
                    runCatching {
                        activity.applicationContext.contentResolver.takePersistableUriPermission(
                            destinationUri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    val outputDir = DocumentFile.fromTreeUri(activity, destinationUri)
                        ?: error("Cannot open the chosen folder")
                    var saved = 0
                    attachments.forEach { attachment ->
                        val documentFile = outputDir.createFile(attachment.mimetype, saveFileName(attachment))
                            ?: return@forEach
                        activity.copyToUri(src = attachment.getUri(), dst = documentFile.uri)
                        saved++
                    }
                    if (saved == 0) error("No file could be created in the chosen folder")
                } else {
                    activity.copyToUri(attachments.first().getUri(), destinationUri)
                }
                activity.toast(org.fossify.commons.R.string.file_saved)
            } catch (e: Exception) {
                activity.showErrorToast(e)
            }
        }
    }
}
