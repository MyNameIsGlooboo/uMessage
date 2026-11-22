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
 * B-23, accepted by the lead's decision of 22 Nov 2026 (step J plan, decision L6): the restore after a 16 to 15 to 16
 * trip does not copy `spam_threads`. The user data it does copy comes back; the Spam rows of the backup, with their
 * unread count and `spam_since`, do not. The app-start sort adds the rows again (not tested here). The number is made
 * up.
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

    /**
     * The backup the older build made of the schema-16 file: a draft, and Spam rows with unread texts for [THREAD] and
     * for [BACKUP_ONLY], a chat the live database has no row of.
     */
    private fun backupWithSpamRows() {
        MessagesDatabase.build(context, OTHER_DB).use { db ->
            offMainThread {
                db.DraftsDao().insertOrUpdate(Draft(threadId = THREAD, body = "kept draft", date = 1))
                val dao = db.ConversationsDao()
                dao.insertSpamThread(SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, unread = 3, spamSince = SPAM_SINCE))
                dao.insertSpamThread(
                    SpamThread(BACKUP_ONLY, SENDER, SpamCause.UNKNOWN, unread = 2, spamSince = SPAM_SINCE)
                )
                // The backup really holds what the restore must leave out.
                assertThat(dao.getSpamThreads().map { it.threadId }).containsExactly(THREAD, BACKUP_ONLY)
            }
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, MessagesDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup)
    }

    /** The restore ran: the draft is back and the backup is retired. */
    private fun assertRestored(db: MessagesDatabase) {
        assertThat(offMainThread { db.DraftsDao().getAll().map { it.body } }).containsExactly("kept draft")
        assertThat(MessagesDatabase.downgradeBackup(context, TEST_DB, MessagesDatabase.VERSION).exists()).isFalse()
    }

    @Test
    fun aDowngradeTripRestoresTheUserDataButNotTheSpamRows() {
        liveDatabase()
        backupWithSpamRows()

        MessagesDatabase.build(context, TEST_DB).use { db ->
            assertRestored(db)
            // Accepted loss: the Spam rows, their unread counts and their spam_since are not copied.
            assertThat(offMainThread { db.ConversationsDao().getSpamThreads() }).isEmpty()
        }
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
        backupWithSpamRows()

        MessagesDatabase.build(context, TEST_DB).use { db ->
            assertRestored(db)
            val rows = offMainThread { db.ConversationsDao().getSpamThreads() }
            // Only the live row is there, with its own values; the backup-only chat gets no row.
            assertThat(rows.map { Triple(it.threadId, it.unread, it.spamSince) })
                .containsExactly(Triple(THREAD, 0, LIVE_SINCE))
        }
    }

    private companion object {
        const val TEST_DB = "messages-recovery-spam-test.db"
        const val OTHER_DB = "messages-recovery-spam-other.db"
        const val THREAD = 7L
        const val BACKUP_ONLY = 8L
        const val SENDER = "+15550001234"
        const val SPAM_SINCE = 1_000L
        const val LIVE_SINCE = 5_000L
    }
}
