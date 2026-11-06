package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Schema 5 adds the forward_log table; everything else is kept as it was. */
@RunWith(AndroidJUnit4::class)
class ForwardLogMigrationTest {

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
    fun migratesFromVersionFourAndAddsAnEmptyLogKeepingForwardRules() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL("INSERT INTO forward_rules (targets, createdAt) VALUES ('+15550001111', 5)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM forward_rules").use {
                assertThat(it.moveToFirst() && it.getInt(0) == 1).isTrue()
            }
            db.query("SELECT COUNT(*) FROM forward_log").use {
                assertThat(it.moveToFirst() && it.getInt(0) == 0).isTrue()
            }
        }
    }

    @Test
    fun recorderFollowsAForwardFromPendingToFailed(): Unit = offMainThread {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.forwardLogDao()
            var clock = 1_000L
            val recorder = ForwardLogRecorder(dao) { clock }
            val id = recorder.begin(7, "BankX", 500, false, "+15550001111", 2)
            assertThat(dao.get(id!!)!!.status).isEqualTo(ForwardStatus.PENDING)

            clock = 2_000
            recorder.sent(id)
            assertThat(dao.get(id)!!.status).isEqualTo(ForwardStatus.SENT)

            clock = 3_000
            assertThat(recorder.radioFailed(id)).isTrue()
            val failed = dao.get(id)!!
            assertThat(failed.status).isEqualTo(ForwardStatus.FAILED)
            assertThat(failed.errorCode).isEqualTo("RADIO_FAILED")

            recorder.retryStarted(id)
            assertThat(dao.get(id)!!.attempts).isEqualTo(2)
        } finally {
            db.close()
        }
    }

    /** H2: the radio's failure marks exactly the forward it belongs to, not the newest one to the same number. */
    @Test
    fun radioFailureMarksExactlyItsOwnRow(): Unit = offMainThread {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.forwardLogDao()
            val recorder = ForwardLogRecorder(dao) { 1_000L }
            val first = recorder.begin(7, "BankX", 500, false, "+15550001111", null)!!
            val second = recorder.begin(7, "BankX", 600, false, "+15550001111", null)!!
            recorder.sent(first)
            recorder.sent(second)

            assertThat(recorder.radioFailed(first)).isTrue()

            assertThat(dao.get(first)!!.status).isEqualTo(ForwardStatus.FAILED)
            assertThat(dao.get(second)!!.status).isEqualTo(ForwardStatus.SENT)
        } finally {
            db.close()
        }
    }

    /** A radio failure reported before the hand-over returned is not overwritten by "sent". */
    @Test
    fun anEarlyRadioFailureIsNotOverwrittenBySent(): Unit = offMainThread {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.forwardLogDao()
            val recorder = ForwardLogRecorder(dao) { 1_000L }
            val id = recorder.begin(7, "BankX", 500, false, "+15550001111", null)!!

            recorder.radioFailed(id)
            recorder.sent(id)

            assertThat(dao.get(id)!!.status).isEqualTo(ForwardStatus.FAILED)
        } finally {
            db.close()
        }
    }

    @Test
    fun skippedRowsAreWrittenWithTheirLimitAndNeverMarkedFailed(): Unit = offMainThread {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.forwardLogDao()
            val recorder = ForwardLogRecorder(dao) { 1_000L }
            val id = recorder.begin(
                7, "BankX", 500, false, "+15550001111", null, ForwardStatus.SKIPPED, "DAILY_LIMIT",
            )!!

            assertThat(recorder.radioFailed(id)).isFalse()
            val row = dao.get(id)!!
            assertThat(row.status).isEqualTo(ForwardStatus.SKIPPED)
            assertThat(row.errorCode).isEqualTo("DAILY_LIMIT")
            assertThat(row.attempts).isEqualTo(0)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "forward-log-migration-test.db"
    }
}
