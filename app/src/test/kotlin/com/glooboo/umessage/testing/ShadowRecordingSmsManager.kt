package com.glooboo.umessage.testing

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSmsManager

/**
 * SmsManager for every test (registered in robolectric.properties, because Robolectric keeps one SmsManager per
 * sandbox). Records every text and multimedia send in static lists, splits text into fixed [PART_LENGTH]-character
 * parts so multipart tests are predictable, and throws for destinations in [failingDestinations], as the platform
 * does when it refuses a send. Call [reset] before each test that uses it.
 */
@Implements(SmsManager::class)
class ShadowRecordingSmsManager : ShadowSmsManager() {

    data class TextSend(
        val destination: String,
        val parts: List<String>,
        val sentIntents: List<PendingIntent>,
        /** The delivery-report intents actually asked for (null entries left out). */
        val deliveryIntents: List<PendingIntent> = emptyList(),
    )

    @Implementation
    fun divideMessage(text: String): ArrayList<String> =
        ArrayList(text.chunked(PART_LENGTH).ifEmpty { listOf("") })

    @Implementation
    override fun sendMultipartTextMessage(
        destinationAddress: String,
        scAddress: String?,
        parts: ArrayList<String>,
        sentIntents: ArrayList<PendingIntent>?,
        deliveryIntents: ArrayList<PendingIntent>?,
    ) {
        if (destinationAddress in failingDestinations) {
            error("refused by the test SmsManager")
        }
        textSends.add(
            TextSend(destinationAddress, parts.toList(), sentIntents.orEmpty().toList(), deliveryIntents.orEmpty().filterNotNull())
        )
    }

    @Implementation
    override fun sendMultimediaMessage(
        context: Context?,
        contentUri: Uri?,
        locationUrl: String?,
        configOverrides: Bundle?,
        sentIntent: PendingIntent?,
    ) {
        multimediaSends.add(contentUri)
    }

    @Implementation
    override fun sendMultimediaMessage(
        context: Context?,
        contentUri: Uri?,
        locationUrl: String?,
        configOverrides: Bundle?,
        sentIntent: PendingIntent?,
        messageId: Long,
    ) {
        multimediaSends.add(contentUri)
    }

    companion object {
        const val PART_LENGTH = 10

        val textSends: MutableList<TextSend> = java.util.Collections.synchronizedList(ArrayList())
        val multimediaSends: MutableList<Uri?> = java.util.Collections.synchronizedList(ArrayList())
        val failingDestinations: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        fun reset() {
            textSends.clear()
            multimediaSends.clear()
            failingDestinations.clear()
        }
    }
}
