package com.glooboo.umessage.databases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-23, accepted (DECISIONS.md, 22 Nov 2026): the restore after a 16 to 15 to 16 trip does not copy `spam_threads`.
 * The user data it does copy comes back; the Spam rows of the backup, with their unread count and `spam_since`, do
 * not. The app-start sort adds the rows again (not tested here). The number is made up.
 */
@RunWith(AndroidJUnit4::class)
class MessagesDatabaseRecoverySpamThreadsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        val dir = context.getDatabasePath(TEST_DB).parentFile!!
        dir.listFiles { _, name -> name.startsWith(TEST_DB) || name.startsWith(OTHER_DB) }?.forEach { it.delete() }
    }

    private fun <T> MessagesDatabase.use(block: (MessagesDatabase) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    /** The live file, as the trip back up leaves it: opened, no Spam rows. */
    private fun liveDatabase() = MessagesDatabase.build(context, TEST_DB).use { db ->
        offMainThread { db.openHelper.writableDatabase }
    }

    /** The backup the older build made of the schema-16 file: a draft and a Spam row with unread texts. */
    private fun backupWithASpamRow() {
        MessagesDatabase.build(context, OTHER_DB).use { db ->
            offMainThread {
                db.DraftsDao().insertOrUpdate(Draft(threadId = THREAD, body = "kept draft", date = 1))
                db.ConversationsDao().insertSpamThread(
                    SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, unread = 3, spamSince = SPAM_SINCE)
                )
            }
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, MessagesDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup)
    }

    @Test
    fun aDowngradeTripRestoresTheUserDataButNotTheSpamRows() {
        liveDatabase()
        backupWithASpamRow()

        MessagesDatabase.build(context, TEST_DB).use { db ->
            offMainThread {
                // The restore ran: the draft is back.
                assertThat(db.DraftsDao().getAll().map { it.body }).containsExactly("kept draft")
                // Accepted loss: the Spam row, its unread count and its spam_since are not copied.
                assertThat(db.ConversationsDao().getSpamThreads()).isEmpty()
            }
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, MessagesDatabase.VERSION)
        assertThat(backup.exists()).isFalse()
    }

    @Test
    fun aSpamRowOfTheLiveDatabaseIsLeftAsItIs() {
        // The restore never touches spam_threads: a row the live database already has keeps its own values.
        MessagesDatabase.build(context, TEST_DB).use { db ->
            offMainThread {
                db.ConversationsDao().insertSpamThread(
                    SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, unread = 0, spamSince = LIVE_SINCE)
                )
            }
        }
        backupWithASpamRow()

        MessagesDatabase.build(context, TEST_DB).use { db ->
            val row = offMainThread { db.ConversationsDao().getSpamThread(THREAD) }!!
            assertThat(row.unread).isEqualTo(0)
            assertThat(row.spamSince).isEqualTo(LIVE_SINCE)
        }
    }

    private companion object {
        const val TEST_DB = "messages-recovery-spam-test.db"
        const val OTHER_DB = "messages-recovery-spam-other.db"
        const val THREAD = 7L
        const val SENDER = "+15550001234"
        const val SPAM_SINCE = 1_000L
        const val LIVE_SINCE = 5_000L
    }
}
