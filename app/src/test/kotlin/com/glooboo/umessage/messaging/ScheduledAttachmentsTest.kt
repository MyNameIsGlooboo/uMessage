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

        // An hour later: a folder written just now may belong to a row about to be saved (M-P7).
        offMainThread { context.tidyScheduledAttachments(nowMillis = System.currentTimeMillis() + 3_600_000L) }
        photo.delete()

        val adopted = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(context.hasExternalScheduledAttachments(adopted)).isFalse()
        assertThat(context.canReadScheduledAttachments(adopted)).isTrue()
        assertThat(orphan.exists()).isFalse()
    }

    @Test
    fun appStartLeavesASendInProgressAndANewRepeatFolderAlone() {
        // M-P7: an alarm is sending message 7001 while the app starts, and has just copied the photos of the next
        // repeat (8002) into their folder; that row is not saved yet.
        val photo = pickedPhoto()
        offMainThread { context.messagesDB.insertOrUpdate(scheduled(Uri.fromFile(photo))) }
        assertThat(offMainThread { ScheduledSendClaims.claim(context, 7001L) }).isTrue()
        val nextRepeat = File(keptRoot, "8002").apply { mkdirs(); File(this, "x.jpg").writeBytes(byteArrayOf(1)) }

        try {
            offMainThread { context.tidyScheduledAttachments(nowMillis = System.currentTimeMillis() + 3_600_000L) }
        } finally {
            ScheduledSendClaims.release(7001L)
        }

        val sending = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(sending.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        assertThat(context.hasExternalScheduledAttachments(sending)).isTrue()
        assertThat(File(keptRoot, "7001").exists()).isFalse()
        assertThat(nextRepeat.exists()).isTrue()

        // Without a send running, a folder written a moment ago is kept too.
        offMainThread { context.tidyScheduledAttachments() }
        assertThat(nextRepeat.exists()).isTrue()
    }

    @Test
    fun aSendThatClaimsTheRowBetweenTheCheckAndTheWriteIsNotOverwritten() {
        // M-P7: the tidy read the row while it was queued; a send claimed it (queued to outbox) before the write.
        val photo = pickedPhoto()
        val snapshot = scheduled(Uri.fromFile(photo))
        offMainThread { context.messagesDB.insertOrUpdate(snapshot) }
        assertThat(offMainThread { ScheduledSendClaims.claim(context, 7001L) }).isTrue()
        // Released, so only the database guard stands between the tidy and the claimed row.
        ScheduledSendClaims.release(7001L)

        offMainThread { context.adoptScheduledAttachments(snapshot) }

        val row = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(row.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        assertThat(row.attachment!!.attachments.single().getUri()).isEqualTo(Uri.fromFile(photo))
        assertThat(File(keptRoot, "7001").exists()).isFalse()
    }

    @Test
    fun aRowDeletedAfterTheSnapshotIsNotCreatedAgainAndItsNewCopiesGo() {
        // M-P7: the tidy read the row, then the send finished and deleted it.
        val snapshot = scheduled(Uri.fromFile(pickedPhoto()))
        offMainThread { context.messagesDB.insertOrUpdate(snapshot) }
        offMainThread { context.messagesDB.deleteScheduledMessage(7001L) }

        offMainThread { context.adoptScheduledAttachments(snapshot) }

        assertThat(offMainThread { context.messagesDB.getAllScheduledMessages() }).isEmpty()
        assertThat(File(keptRoot, "7001").exists()).isFalse()
    }

    @Test
    fun aSendThatClaimsTheRowWhileThePhotoIsCopiedIsNotOverwritten() {
        // M-P7: the alarm claims the row while the tidy is still reading the photo; the in-memory check before the
        // write sees it, so the row keeps the picker's uri and the copies made meanwhile are deleted again.
        Robolectric.setupContentProvider(ClaimingPickerProvider::class.java, PICKER_AUTHORITY)
        pickedPhoto()
        val pickerUri = Uri.parse("content://$PICKER_AUTHORITY/picker-photo.jpg")
        val snapshot = scheduled(pickerUri)
        offMainThread { context.messagesDB.insertOrUpdate(snapshot) }

        try {
            offMainThread { context.adoptScheduledAttachments(snapshot) }
        } finally {
            ScheduledSendClaims.release(7001L)
        }

        val row = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(row.attachment!!.attachments.single().getUri()).isEqualTo(pickerUri)
        assertThat(File(keptRoot, "7001").exists()).isFalse()
    }

    @Test
    fun aRefusedWriteKeepsTheRowsOwnCopyAndDropsOnlyTheNewOne() {
        // K1-R3: photo A is already the app's own copy, photo B still the picker's; the row was claimed meanwhile.
        val ownA = context.keepScheduledAttachments(scheduled(Uri.fromFile(pickedPhoto("a.jpg"))))
            .attachment!!.attachments.single().getUri()
        val ownFile = File(keptRoot, "7001").listFiles()!!.single()
        val snapshot = scheduled(ownA, Uri.fromFile(pickedPhoto("b.jpg")))
        offMainThread { context.messagesDB.insertOrUpdate(snapshot) }
        assertThat(offMainThread { ScheduledSendClaims.claim(context, 7001L) }).isTrue()
        ScheduledSendClaims.release(7001L)

        offMainThread { context.adoptScheduledAttachments(snapshot) }

        assertThat(File(keptRoot, "7001").listFiles()!!.toList()).containsExactly(ownFile)
        assertThat(read(ownA)).isEqualTo(byteArrayOf(1, 2, 3))
        val row = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        val uris = { message: Message -> message.attachment!!.attachments.map { it.getUri() } }
        assertThat(uris(row)).isEqualTo(uris(snapshot))
    }

    @Test
    fun aHeldRowIsNotChangedByTheQueuedOnlyWrite() {
        // K1-R3: held as failed (5), waiting for the user's Retry.
        val photo = Uri.fromFile(pickedPhoto())
        offMainThread {
            context.messagesDB.insertOrUpdate(scheduled(photo).copy(type = Telephony.Sms.MESSAGE_TYPE_FAILED))
        }

        val changed = offMainThread {
            context.messagesDB.updateQueuedScheduledAttachment(7001L, MessageAttachment(7001L, "", ArrayList()))
        }

        assertThat(changed).isEqualTo(0)
        val row = offMainThread { context.messagesDB.getAllScheduledMessages() }.single()
        assertThat(row.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)
        assertThat(row.attachment!!.attachments.single().getUri()).isEqualTo(photo)
    }

    /** A picker that hands the photo over only after scheduled message 7001 was claimed by a send. */
    class ClaimingPickerProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            ScheduledSendClaims.claim(context!!, 7001L)
            val file = File(context!!.cacheDir, uri.lastPathSegment!!)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private companion object {
        const val PICKER_AUTHORITY = "test.picker"
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
