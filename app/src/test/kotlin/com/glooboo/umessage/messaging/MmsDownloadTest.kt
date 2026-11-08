package com.glooboo.umessage.messaging

import android.provider.Telephony
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.ui.thread.ThreadRow
import com.glooboo.umessage.ui.thread.buildThreadItems
import com.glooboo.umessage.ui.thread.buildThreadRows
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Q40: the "Tap to download" row for a picture message that was never downloaded. */
class MmsDownloadTest {
    private val now = 1_700_000_000_000L
    private val pending = PendingMms(7, "http://mmsc/x", "tx1", 0, 1)

    private fun mms(
        id: Long, received: Boolean = true, body: String = "", parts: Int = 0,
    ) = Message(
        id = id, body = body,
        type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
        status = 0, participants = ArrayList(), date = id.toInt() * 1000, read = true, threadId = 1, isMMS = true,
        attachment = MessageAttachment(
            id, body,
            ArrayList((0 until parts).map { Attachment(null, id, "content://p/$it", "image/jpeg", 1, 1, "f") }),
        ),
        senderPhoneNumber = "+1", senderName = "A", senderPhotoUri = "", subscriptionId = 1,
    )

    @Test
    fun onlyAnEmptyReceivedMmsIsACandidate() {
        assertThat(MmsDownloadRules.isCandidate(mms(1))).isTrue()
        assertThat(MmsDownloadRules.isCandidate(mms(1, body = "hi"))).isFalse()
        assertThat(MmsDownloadRules.isCandidate(mms(1, parts = 1))).isFalse()
        assertThat(MmsDownloadRules.isCandidate(mms(1, received = false))).isFalse()
        assertThat(MmsDownloadRules.isCandidate(mms(1).copy(isMMS = false))).isFalse()
    }

    @Test
    fun expiryIsOnlyKnownWhenSet() {
        assertThat(MmsDownloadRules.isExpired(0, now)).isFalse()
        assertThat(MmsDownloadRules.isExpired(now / 1000 + 10, now)).isFalse()
        assertThat(MmsDownloadRules.isExpired(now / 1000 - 10, now)).isTrue()
    }

    @Test
    fun stateFollowsTheTrackerAndExpiry() {
        val past = now / 1000 - 10
        val running = MmsDownloadTracker.Entry.Downloading(now - 1000)
        assertThat(MmsDownloadRules.stateOf(null, 0, now)).isEqualTo(MmsDownloadState.NotDownloaded)
        // Expired but never tried: the carrier may still serve it, so the button stays.
        assertThat(MmsDownloadRules.stateOf(null, past, now)).isEqualTo(MmsDownloadState.NotDownloaded)
        assertThat(MmsDownloadRules.stateOf(running, 0, now)).isEqualTo(MmsDownloadState.Downloading)
        assertThat(MmsDownloadRules.stateOf(MmsDownloadTracker.Entry.Failed, 0, now))
            .isEqualTo(MmsDownloadState.Failed)
        assertThat(MmsDownloadRules.stateOf(MmsDownloadTracker.Entry.Failed, past, now))
            .isEqualTo(MmsDownloadState.Expired)
    }

    @Test
    fun aDownloadThatReportsNothingBecomesFailed() {
        val stale = MmsDownloadTracker.Entry.Downloading(now - MmsDownloadRules.DOWNLOAD_TIMEOUT_MS)
        assertThat(MmsDownloadRules.stateOf(stale, 0, now)).isEqualTo(MmsDownloadState.Failed)
    }

    @Test
    fun trackerRefusesASecondDownloadAndFailsEveryRunningOne() {
        val tracker = MmsDownloadTracker()
        assertThat(tracker.begin(1, now)).isTrue()
        assertThat(tracker.begin(1, now + 1)).isFalse()
        assertThat(tracker.begin(2, now)).isTrue()
        tracker.failAllRunning()
        assertThat(tracker.state.value.values).containsExactly(
            MmsDownloadTracker.Entry.Failed, MmsDownloadTracker.Entry.Failed,
        )
        assertThat(tracker.begin(1, now + 2)).isTrue()
        tracker.forget(1)
        assertThat(tracker.state.value.keys).containsExactly(2L)
    }

    @Test
    fun theTapStartsTheDownloadOnce() {
        val tracker = MmsDownloadTracker()
        val started = ArrayList<PendingMms>()
        fun tap() = retryMmsDownload(7, tracker, now, { pending }, { started += it })
        assertThat(tap()).isEqualTo(MmsRetryResult.Started)
        assertThat(tap()).isEqualTo(MmsRetryResult.AlreadyDownloading)
        assertThat(started).containsExactly(pending)
        assertThat(tracker.state.value[7]).isInstanceOf(MmsDownloadTracker.Entry.Downloading::class.java)
    }

    @Test
    fun aGoneRowStartsNothingAndAFailedStartIsMarkedFailed() {
        val tracker = MmsDownloadTracker()
        assertThat(retryMmsDownload(7, tracker, now, { null }, { error("never") }))
            .isEqualTo(MmsRetryResult.NotPending)
        assertThat(tracker.state.value).isEmpty()
        val result = retryMmsDownload(7, tracker, now, { pending }, { throw IllegalStateException("no data") })
        assertThat(result).isEqualTo(MmsRetryResult.StartFailed)
        assertThat(tracker.state.value[7]).isEqualTo(MmsDownloadTracker.Entry.Failed)
        // A failed one can be tapped again.
        assertThat(retryMmsDownload(7, tracker, now, { pending }, { })).isEqualTo(MmsRetryResult.Started)
    }

    @Test
    fun theChatShowsTheRowInPlaceOfTheEmptyBubble() {
        val messages = listOf(mms(1, body = "text"), mms(2))
        val items = buildThreadItems(messages, emptyMap(), seen = false, undownloadedMms = mapOf(2L to 55L)).items
        assertThat(items.filterIsInstance<ThreadItem.ThreadMmsDownload>())
            .containsExactly(ThreadItem.ThreadMmsDownload(2, 55))
        assertThat(items.filterIsInstance<Message>().map { it.id }).containsExactly(1L)
        val rows = buildThreadRows(items, false).filterIsInstance<ThreadRow.MmsDownload>()
        assertThat(rows.map { it.messageId to it.expirySeconds }).containsExactly(2L to 55L)
    }

    @Test
    fun withoutPendingRowsNothingChanges() {
        val items = buildThreadItems(listOf(mms(2)), emptyMap(), seen = false).items
        assertThat(items.filterIsInstance<ThreadItem.ThreadMmsDownload>()).isEmpty()
        assertThat(items.filterIsInstance<Message>()).hasSize(1)
    }
}
