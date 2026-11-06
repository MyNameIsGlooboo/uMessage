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

    fun add(uri: Uri) {
        val id = uri.toString()
        val check = checkAttachment(
            alreadyAdded = vm.hasAttachment(id),
            mimeType = activity.contentResolver.getType(uri),
            sizeLimit = activity.config.mmsFileSizeLimit,
            fileSize = { activity.getFileSizeFromUri(uri) },
        )
        when (check) {
            AttachmentCheck.Duplicate -> activity.toast(R.string.duplicate_item_warning)
            AttachmentCheck.UnknownType -> activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
            AttachmentCheck.TooLarge -> activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
            is AttachmentCheck.Ok -> {
                val attachment = AttachmentSelection(
                    id = id,
                    uri = uri,
                    mimetype = check.mimeType,
                    filename = activity.getFilenameFromUri(uri),
                    isPending = check.compress,
                )
                vm.addAttachment(attachment)
                if (attachment.isPending) compress(attachment)
                onChanged()
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
        AudioPlayerManager.release(attachment.uri)
        vm.removeAttachment(attachment.id)
        onChanged()
    }

    /** Empties the composer's attachments (after a send), stopping any that was playing. */
    fun clear() {
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
