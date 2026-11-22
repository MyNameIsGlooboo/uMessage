package com.glooboo.umessage.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.FakeMmsProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two store queries of the provider-truth MMS path (step E) on a stand-in of the MMS provider: the MMS the library
 * stored but did not report (E-17), and the notices without a chat the sweep places (E-20).
 */
@RunWith(AndroidJUnit4::class)
class MmsDownloadQueriesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var mms: FakeMmsProvider

    @Before
    fun setUp() {
        mms = FakeMmsProvider.install()
    }

    @Test
    fun e17_theStoredMms_isFoundByItsTransactionIdOrLocation_storedSinceTheNotice_andUnread() {
        val byId = mms.addMms(threadId = 1L, transactionId = "T-1", dateSeconds = SINCE)
        val byLocation = mms.addMms(threadId = 2L, location = LOCATION, dateSeconds = SINCE + 5)

        assertThat(context.storedMmsFor("T-1", "", SINCE)?.lastPathSegment).isEqualTo("$byId")
        assertThat(context.storedMmsFor("", LOCATION, SINCE)?.lastPathSegment).isEqualTo("$byLocation")
    }

    @Test
    fun e17_rowsStoredBeforeTheNotice_readOrSeen_orNotices_areNotTakenForIt() {
        mms.addMms(threadId = 1L, transactionId = "T-1", dateSeconds = SINCE - 1)
        mms.addMms(threadId = 2L, transactionId = "T-1", dateSeconds = SINCE, read = true)
        mms.addNotice("+15550001234", transactionId = "T-1", location = LOCATION, dateSeconds = SINCE)

        assertThat(context.storedMmsFor("T-1", LOCATION, SINCE)).isNull()
    }

    @Test
    fun e17_emptyKeysAreNotAsked_soARowWithoutThemIsNeverMatched() {
        mms.addMms(threadId = 1L, transactionId = "", location = "", dateSeconds = SINCE)

        assertThat(context.storedMmsFor("", "", SINCE)).isNull()
        assertThat(context.storedMmsFor("T-9", "", SINCE)).isNull()
    }

    @Test
    fun e20_chatlessNotices_areTheOnesWithoutAChatInTheTimeWindow_newestFirst() {
        val old = mms.addNotice("+15550001234", transactionId = "T-1", dateSeconds = SINCE)
        val newer = mms.addNotice("+15550001234", transactionId = "T-2", dateSeconds = SINCE + 10)
        mms.addNotice("+15550001234", threadId = 7L, transactionId = "T-3", dateSeconds = SINCE)
        mms.addNotice("+15550001234", transactionId = "T-4", dateSeconds = SINCE + 1_000)
        mms.addNotice("+15550001234", transactionId = "T-5", dateSeconds = SINCE - 1_000)

        val found = context.chatlessNotices(before = (SINCE + 500) * 1000, after = (SINCE - 500) * 1000)
        assertThat(found.map { it.uri }).containsExactly(newer, old).inOrder()
    }

    @Test
    fun e25_chatlessNotices_areAllReturned_theBatchIsTheSweepsJob() {
        repeat(25) { mms.addNotice("+15550001234", transactionId = "T-$it", dateSeconds = SINCE + it) }

        val found = context.chatlessNotices(before = (SINCE + 500) * 1000, after = (SINCE - 500) * 1000)

        assertThat(found).hasSize(25)
    }

    private companion object {
        const val SINCE = 1_700_000_000L
        const val LOCATION = "http://mmsc.example.com/msg/stored"
    }
}
