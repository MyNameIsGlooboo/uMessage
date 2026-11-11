package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Schema 7 adds the carrier send time to the pending SMS queue; queued rows keep everything and get 0 (unknown). */
@RunWith(AndroidJUnit4::class)
class PendingSmsDateSentMigrationTest {

    @get:Rule
    val helper = migrationTestHelper(SpamDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile
            ?.listFiles { _, name -> name.startsWith(TEST_DB) }
            ?.forEach { it.delete() }
    }

    @Test
    fun migratesFromVersionSixKeepingQueuedRowsWithAnUnknownSendTime() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO pending_sms (address, subject, body, date, subscriptionId, status, createdAt) " +
                    "VALUES ('Shop', '', 'queued', 5000, 1, 0, 6000)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT body, date, dateSent FROM pending_sms").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("queued")
                assertThat(it.getLong(1)).isEqualTo(5000L)
                assertThat(it.getLong(2)).isEqualTo(0L)
            }
        }
    }

    @Test
    fun theSendTimeIsStoredAndReadBack(): Unit = offMainThread {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.spamDao()
            dao.insertPending(
                PendingSmsEntity(
                    address = "Shop", subject = "", body = "hi", date = 9_000L, subscriptionId = 1, status = 0,
                    createdAt = 9_000L, dateSent = 4_000L,
                )
            )
            val row = dao.getPending().single()
            assertThat(row.date).isEqualTo(9_000L)
            assertThat(row.dateSent).isEqualTo(4_000L)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "pending-date-sent-migration-test.db"
    }
}
