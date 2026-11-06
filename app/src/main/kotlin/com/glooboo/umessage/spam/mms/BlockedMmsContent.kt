package com.glooboo.umessage.spam.mms

import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf

/** What a downloaded MMS (an m-retrieve-conf PDU) holds, as far as the Blocked screen needs it. */
data class BlockedMmsContent(
    /** All text parts joined by newlines; empty when there are none. */
    val text: String,
    /** Data of the first picture part, or null. */
    val firstImage: ByteArray?,
) {
    override fun equals(other: Any?) =
        other is BlockedMmsContent && other.text == text && other.firstImage.contentEquals(firstImage)

    override fun hashCode() = 31 * text.hashCode() + (firstImage?.contentHashCode() ?: 0)

    companion object {
        /** Null when [pdu] is not a parseable downloaded MMS, so it cannot be restored later either. Never throws. */
        fun parse(pdu: ByteArray?): BlockedMmsContent? {
            if (pdu == null || pdu.isEmpty()) return null
            val conf = try {
                PduParser(pdu, true).parse() as? RetrieveConf
            } catch (_: Throwable) {
                null
            } ?: return null
            return try {
                // The carrier answers an expired or unknown MMS with an error status and no content.
                val status = conf.retrieveStatus
                if (status != 0 && status != PduHeaders.RETRIEVE_STATUS_OK) return null
                val body = conf.body ?: return null
                if (body.partsNum == 0) return null
                val texts = mutableListOf<String>()
                var image: ByteArray? = null
                for (i in 0 until body.partsNum) {
                    val part = body.getPart(i) ?: continue
                    val type = part.contentType?.let { String(it, Charsets.ISO_8859_1) }.orEmpty().lowercase()
                    val data = part.data ?: continue
                    when {
                        type == ContentType.TEXT_PLAIN -> texts += decode(data, part.charset)
                        image == null && type.startsWith("image/") -> image = data
                    }
                }
                BlockedMmsContent(texts.filter { it.isNotBlank() }.joinToString("\n").trim(), image)
            } catch (_: Throwable) {
                null
            }
        }

        private fun decode(data: ByteArray, charset: Int): String = try {
            val name = if (charset == 0) CharacterSets.MIMENAME_UTF_8 else CharacterSets.getMimeName(charset)
            String(data, charset(name))
        } catch (_: Throwable) {
            String(data, Charsets.UTF_8)
        }
    }
}
