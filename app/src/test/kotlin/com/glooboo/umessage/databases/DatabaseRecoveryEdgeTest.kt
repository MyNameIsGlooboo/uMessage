package com.glooboo.umessage.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** D10 and D11: an old downgrade backup is never overwritten, and a hopeless one stops being copied at every open. */
@RunWith(AndroidJUnit4::class)
class DatabaseRecoveryEdgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        val dir = context.getDatabasePath(DB).parentFile!!
        dir.listFiles { _, name -> name.startsWith(DB) }?.forEach { it.delete() }
        context.getSharedPreferences("database_recovery_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun newerFile() {
        val file = context.getDatabasePath(DB)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE IF NOT EXISTS t (a INTEGER)")
            it.version = NEWER
        }
    }

    @Test
    fun aSecondDowngradeDoesNotOverwriteAnUnrestoredBackup() {
        newerFile()
        val first = DatabaseBackups.moveAsideIfNewer(context, DB, MessagesDatabase.VERSION)!!
        first.writeText("first")
        val firstText = first.readText()
        newerFile()

        val second = DatabaseBackups.moveAsideIfNewer(context, DB, MessagesDatabase.VERSION)!!

        assertThat(second.exists()).isTrue()
        val kept = File(first.path.removeSuffix("-backup") + "-earlier-1")
        assertThat(kept.readText()).isEqualTo(firstText)
    }

    @Test
    fun aBackupThatKeepsFailingIsRetiredAfterFiveOpens() {
        val backup = MessagesDatabase.downgradeBackup(context, DB, MessagesDatabase.VERSION)
        backup.parentFile?.mkdirs()
        backup.writeText("not a database")

        repeat(RecoveryAttempts.MAX_FAILURES - 1) {
            MessagesDatabase.build(context, DB).apply { offMainThread { openHelper.writableDatabase } }.close()
        }
        assertThat(backup.exists()).isTrue()
        MessagesDatabase.build(context, DB).apply { offMainThread { openHelper.writableDatabase } }.close()

        assertThat(backup.exists()).isFalse()
        assertThat(File(backup.path.removeSuffix("-backup") + "-failed").exists()).isTrue()
    }

    private companion object {
        const val DB = "recovery-edge-test.db"
        const val NEWER = MessagesDatabase.VERSION + 1
    }
}
