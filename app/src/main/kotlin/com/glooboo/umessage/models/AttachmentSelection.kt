package com.glooboo.umessage.models

import android.net.Uri
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.extensions.isPlayableAudioMimeType
import com.glooboo.umessage.extensions.isVCardMimeType
import com.glooboo.umessage.extensions.isVideoMimeType
import com.glooboo.umessage.helpers.ATTACHMENT_DOCUMENT
import com.glooboo.umessage.helpers.ATTACHMENT_MEDIA
import com.glooboo.umessage.helpers.ATTACHMENT_VCARD
import com.glooboo.umessage.helpers.ATTACHMENT_AUDIO

data class AttachmentSelection(
    val id: String,
    val uri: Uri,
    val mimetype: String,
    val filename: String,
    var isPending: Boolean,
    val viewType: Int = getViewTypeForMimeType(mimetype)
) {
    companion object {
        fun getViewTypeForMimeType(mimetype: String): Int {
            return when {
                mimetype.isImageMimeType() || mimetype.isVideoMimeType() -> ATTACHMENT_MEDIA
                mimetype.isPlayableAudioMimeType() -> ATTACHMENT_AUDIO
                mimetype.isVCardMimeType() -> ATTACHMENT_VCARD
                else -> ATTACHMENT_DOCUMENT
            }
        }

        fun areItemsTheSame(first: AttachmentSelection, second: AttachmentSelection): Boolean {
            return first.id == second.id
        }

        fun areContentsTheSame(first: AttachmentSelection, second: AttachmentSelection): Boolean {
            return first.uri == second.uri && first.mimetype == second.mimetype && first.filename == second.filename
        }
    }
}
