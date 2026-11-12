package com.glooboo.umessage.messaging

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.receivers.ScheduledMessageReceiver
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException

/**
 * TU-15: a scheduled MMS goes out long after the photo was picked, when the picker's uri is often unreadable. The
 * app keeps its own copy of each attachment, sends from it, and deletes it afterwards. A message scheduled before
 * this (it only has the picker's uri) that can no longer be read is held with a notification saying why.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledAttachmentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keptRoot get() = File(context.filesDir, "scheduled_attachments")
    private val notifications get() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        Robolectric.setupContentProvider(KeptFilesProvider::class.java, "${context.packageName}.provider")
    }

    @After
    fun tearDown() {
        keptRoot.deleteRecursively()
        resetSharedMessagesDatabase()
    }

    /** A photo outside the app's control, like one the picker handed over. */
    private fun pickedPhoto(name: String = "photo.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(context.cacheDir, "picker-$name").apply { writeBytes(bytes) }

    private fun scheduled(vararg uris: Uri, id: Long = 7001L) = Message(
        id = id, body = "Happy birthday!", type = Telephony.Sms.MESSAGE_TYPE_QUEUED,
        status = Telephony.Sms.STATUS_NONE,
        participants = arrayListOf(
            SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
        ),
        date = 1, read = false, threadId = id, isMMS = true,
        attachment = MessageAttachment(
            id, "Happy birthday!",
            uris.mapTo(ArrayList()) { Attachment(null, id, it.toString(), "image/jpeg", 0, 0, "photo.jpg") }
        ),
        senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true
    )

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun fire(message: Message) {
        val intent = Intent().putExtra(THREAD_ID, message.threadId).putExtra(SCHEDULED_MESSAGE_ID, message.id)
        offMainThread { ScheduledMessageReceiver().handleIntent(context, intent) }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun theKeptCopyIsReadableAfterTheOriginalIsGone() {
        val photo = pickedPhoto(bytes = byteArrayOf(9, 8, 7))

        val kept = context.keepScheduledAttachments(scheduled(Uri.fromFile(photo)))
        photo.delete()

        val uri = kept.attachment!!.attachments.single().getUri()
        assertThat(uri.authority).isEqualTo("${context.packageName}.provider")
        assertThat(read(uri)).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(context.hasExternalScheduledAttachments(kept)).isFalse()
    }

    @Test
    fun anUnreadablePhotoIsRefusedWhenScheduling() {
        val gone = Uri.fromFile(File(context.cacheDir, "never-existed.jpg"))

        assertThrows(IOException::class.java) { context.keepScheduledAttachments(scheduled(gone)) }
        assertThat(keptRoot.walk().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun editingKeepsTheCopiesStillUsedAndDropsTheOthers() {
        val first = context.keepScheduledAttachments(scheduled(Uri.fromFile(pickedPhoto("a.jpg")),
            Uri.fromFile(pickedPhoto("b.jpg"))))
        val (keptA, keptB) = first.attachment!!.attachments.map { it.getUri() }

        // The user removes photo B and adds photo C while editing.
        val edited = context.keepScheduledAttachments(scheduled(keptA, Uri.fromFile(pickedPhoto("c.jpg"))))

        val uris = edited.attachment!!.attachments.map { it.getUri() }
        assertThat(uris.first()).isEqualTo(keptA)
        assertThat(File(keptRoot, "7001").list()!!.toList()).hasSize(2)
        assertThat(runCatching { read(keptB) }.isFailure).isTrue()
    }

    @Test
    fun aScheduledMmsThatFailsKeepsItsCopyForRetry() {
        // The fake provider cannot store a real MMS, so this send fails and the message is held.
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        val message = context.keepScheduledAttachments(scheduled(Uri.fromFile(pickedPhoto())))
        offMainThread { context.messagesDB.insertOrUpdate(message) }

        fire(message)

        val held = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(held.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)
        assertThat(context.canReadScheduledAttachments(held)).isTrue()
    }

    @Test
    fun aSentScheduledMessageDeletesItsCopies() {
        FakeSmsMmsProvider.installSms()
        // A text-only message stands in for a sent MMS (the fake provider cannot store one); its copies folder
        // must go once the message was handed off.
        val message = scheduled().copy(isMMS = false, attachment = null)
        File(keptRoot, "7001").apply { mkdirs(); File(this, "x.jpg").writeBytes(byteArrayOf(1)) }
        offMainThread { context.messagesDB.insertOrUpdate(message) }

        fire(message)

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(offMainThread { context.messagesDB.getAllScheduledMessages() }).isEmpty()
        assertThat(File(keptRoot, "7001").exists()).isFalse()
    }

    @Test
    fun anOldScheduledMessageWhosePhotoIsGoneIsHeldWithAnHonestNotification() {
        // Scheduled before the app kept copies: only the picker's uri, whose file is gone.
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        val photo = pickedPhoto()
        val message = scheduled(Uri.fromFile(photo))
        photo.delete()
        offMainThread { context.messagesDB.insertOrUpdate(message) }

        fire(message)

        assertThat(ShadowRecordingSmsManager.multimediaSends).isEmpty()
        val held = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(held.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)
        val text = shadowOf(notifications.single()).contentText.toString()
        assertThat(text).contains("Sara")
        assertThat(text).contains("photo.jpg")
    }

    @Test
    fun appStartCopiesOldPickerUrisAndDeletesOrphanCopies() {
        val photo = pickedPhoto()
        offMainThread { context.messagesDB.insertOrUpdate(scheduled(Uri.fromFile(photo))) }
        val orphan = File(keptRoot, "999").apply { mkdirs(); File(this, "x.jpg").writeBytes(byteArrayOf(1)) }

        offMainThread { context.tidyScheduledAttachments() }
        photo.delete()

        val adopted = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(context.hasExternalScheduledAttachments(adopted)).isFalse()
        assertThat(context.canReadScheduledAttachments(adopted)).isTrue()
        assertThat(orphan.exists()).isFalse()
    }

    /**
     * Serves files/<path> like the app's FileProvider does for its `scheduled_attachments` root. The real one is not
     * used: its path matching expects "/" separators and fails on a Windows test machine.
     */
    class KeptFilesProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(context!!.filesDir, uri.pathSegments.joinToString(File.separator))
            if (!file.exists()) throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
