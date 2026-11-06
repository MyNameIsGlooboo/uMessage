package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** SU-17 / SE-29: "Not spam" on picture messages says what really came back. */
@RunWith(AndroidJUnit4::class)
class BlockedMmsRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private lateinit var vm: BlockedViewModel

    @Volatile private var mmsDeliveryWorks = true
    private val deliveredMms = Collections.synchronizedList(mutableListOf<BlockedMessage>())

    private val platform = object : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 1L
        override fun deliverRestoredMms(message: BlockedMessage): Long? {
            if (!mmsDeliveryWorks) return null
            deliveredMms += message
            return 77L
        }

        override fun removeRestored(id: Long, isMms: Boolean) = true
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        repo = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "US" })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun start(vararg messages: BlockedMessage) {
        runBlocking { messages.forEach { repo.storeBlocked(it) } }
        vm = BlockedViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { vm.state.value.groups.isNotEmpty() }
    }

    private fun mms(body: String = "", media: MmsMedia = MmsMedia.MISSING, file: String? = null, at: Long = 0) =
        BlockedMessage(
            address = SENDER, body = body, subscriptionId = 1, isMms = true,
            receivedAt = System.currentTimeMillis() - at, reason = DecisionReason.UNKNOWN_SENDER,
            media = media, mediaFile = file,
        )

    private fun notSpamAndAnswer(): BlockedEvent.Restored {
        vm.notSpam(setOf(SENDER))
        awaitUntil { events.isNotEmpty() }
        return events.first() as BlockedEvent.Restored
    }

    /** The case that used to say "1 message restored" while nothing reached the inbox. */
    @Test
    fun anUndownloadedMmsIsNotCountedAsRestored() {
        start(mms())
        val event = notSpamAndAnswer()
        assertThat(event.count).isEqualTo(0)
        assertThat(event.mediaLost).isEqualTo(1)
        assertThat(restoredSnackText(app.resources, event)).isEqualTo(
            app.resources.getQuantityString(R.plurals.spam_snack_media_lost_nothing, 1, 1)
        )
    }

    @Test
    fun anMmsWithTextButNoPicturesSaysThePicturesAreLost() {
        start(mms(body = "Look at this"))
        val event = notSpamAndAnswer()
        assertThat(event.count).isEqualTo(1)
        assertThat(event.mediaLost).isEqualTo(1)
        val text = restoredSnackText(app.resources, event)
        assertThat(text).contains(app.resources.getString(R.string.nsf_snack_moved_allowed, SENDER))
        assertThat(text).contains(app.resources.getQuantityString(R.plurals.spam_snack_media_lost, 1, 1))
    }

    @Test
    fun aKeptMmsIsRestoredWithItsPicturesAndNoWarning() {
        start(mms(media = MmsMedia.STORED, file = FILE))
        val event = notSpamAndAnswer()
        assertThat(event.count).isEqualTo(1)
        assertThat(event.mediaLost).isEqualTo(0)
        assertThat(restoredSnackText(app.resources, event))
            .isEqualTo(app.resources.getString(R.string.nsf_snack_moved_allowed, SENDER))
        // In the inbox before the undo window closes.
        assertThat(deliveredMms.single().mediaFile).isEqualTo(FILE)

        vm.commitRestore(event.token)
        awaitUntil { vm.state.value.stats.restoredTotal == 1 }
        assertThat(deliveredMms).hasSize(1)
    }

    /** Undo of a kept MMS puts it back in Blocked with its pictures still kept. */
    @Test
    fun undoOfAKeptMmsBringsItBackWithItsPictures() {
        start(mms(media = MmsMedia.STORED, file = FILE))
        val event = notSpamAndAnswer()
        vm.undoRestore(event.token)
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.size == 1 }
        val back = vm.state.value.groups.single().messages.single()
        assertThat(back.mediaFile).isEqualTo(FILE)
        assertThat(back.hasStoredMedia).isTrue()
    }

    @Test
    fun aKeptMmsThatCannotBeRestoredIsReportedAndStaysInBlocked() {
        mmsDeliveryWorks = false
        start(mms(media = MmsMedia.STORED, file = FILE))
        vm.notSpam(setOf(SENDER))

        awaitUntil { events.any { it is BlockedEvent.RestoreFailed } }
        assertThat(events.filterIsInstance<BlockedEvent.RestoreFailed>().single().count).isEqualTo(1)
        // Nothing left Blocked, so there is no "restored" snackbar with an Undo for nothing.
        assertThat(events.filterIsInstance<BlockedEvent.Restored>()).isEmpty()
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.size == 1 }
    }

    @Test
    fun aDownloadStillRunningCountsAsLost() {
        start(mms(media = MmsMedia.DOWNLOADING))
        val event = notSpamAndAnswer()
        assertThat(event.count).isEqualTo(0)
        assertThat(event.mediaLost).isEqualTo(1)
    }

    @Test
    fun rowsSayWhatIsKeptOfAPictureMessage() {
        val res = app.resources
        assertThat(blockedBodyText(res, mms(media = MmsMedia.STORED, file = FILE))).isEqualTo(res.getString(R.string.spam_mms_stored))
        assertThat(blockedBodyText(res, mms(media = MmsMedia.DOWNLOADING))).isEqualTo(res.getString(R.string.spam_mms_downloading))
        assertThat(blockedBodyText(res, mms())).isEqualTo(res.getString(R.string.spam_mms_missing))
        assertThat(blockedBodyText(res, mms(body = MMS_PLACEHOLDER_BODY))).isEqualTo(res.getString(R.string.spam_mms_missing))
        assertThat(blockedBodyText(res, mms(body = "Hello"))).isEqualTo("Hello")
        // A download that never answered is shown as failed, not as downloading forever.
        val stale = mms(media = MmsMedia.DOWNLOADING, at = BlockedMessage.DOWNLOAD_TIMEOUT_MS + 1_000)
        assertThat(blockedBodyText(res, stale)).isEqualTo(res.getString(R.string.spam_mms_missing))
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; events=$events" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+14155550133"
        const val FILE = "0f9c2a3e-1111-2222-3333-444455556666"
    }
}
