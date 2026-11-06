package com.glooboo.umessage.testing

import android.content.Context
import android.graphics.Bitmap
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import java.io.ByteArrayOutputStream

/** Downloaded MMS (m-retrieve-conf PDUs) for tests, built with mmslib's own composer. */
object MmsPduFixtures {

    /**
     * mmslib can only compose outgoing PDUs, so this composes an m-send-req with the same headers and body a
     * retrieve-conf carries and then changes the message type byte (the first header) to m-retrieve-conf.
     */
    fun retrieveConf(context: Context, text: String?, image: ByteArray? = null): ByteArray {
        val req = SendReq()
        req.from = EncodedStringValue("+15551234567")
        req.addTo(EncodedStringValue("+15550000000"))
        req.date = 1_700_000_000L
        req.contentType = "application/vnd.wap.multipart.related".toByteArray()
        val body = PduBody()
        text?.let {
            body.addPart(part("text/plain", "text.txt", it.toByteArray(Charsets.UTF_8)).apply { setCharset(CharacterSets.UTF_8) })
        }
        image?.let { body.addPart(part("image/png", "image.png", it)) }
        req.body = body
        val pdu = PduComposer(context, req).make() ?: error("PduComposer failed")
        check(pdu[0] == 0x8C.toByte() && pdu[1] == 0x80.toByte()) { "unexpected PDU start" }
        pdu[1] = 0x84.toByte() // X-Mms-Message-Type: m-retrieve-conf
        return pdu
    }

    fun png(width: Int = 400, height: Int = 300): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun part(type: String, name: String, data: ByteArray) = PduPart().apply {
        setContentType(type.toByteArray())
        setContentLocation(name.toByteArray())
        setContentId("<$name>".toByteArray())
        setData(data)
    }
}
