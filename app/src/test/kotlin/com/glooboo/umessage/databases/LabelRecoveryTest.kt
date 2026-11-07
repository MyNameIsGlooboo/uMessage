package com.glooboo.umessage.databases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** DA2: labels and reminders of a downgrade backup keep their meaning when their ids collide with live ones. */
@RunWith(AndroidJUnit4::class)
class LabelRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        val dir = context.getDatabasePath(LIVE).parentFile!!
        dir.listFiles { _, name -> name.startsWith(LIVE) || name.startsWith(OTHER) }?.forEach { it.delete() }
    }

    @Test
    fun aCollidingLabelIdDoesNotGiveTheChatsTheWrongLabel() {
        withDb(LIVE) { db ->
            blocking {
                val id = db.LabelsDao().insertLabel(Label(name = "Work", color = 1, sortOrder = 0))
                assertThat(id).isEqualTo(1L)
                db.MessageRemindersDao().insert(MessageReminder(messageId = 1, isMMS = false, threadId = 3,
                    remindAt = 5, snapshotBody = "live"))
            }
        }
        withDb(OTHER) { db ->
            blocking {
                db.LabelsDao().insertLabel(Label(name = "Family", color = 2, sortOrder = 1))
                db.LabelsDao().addToConversation(ConversationLabel(threadId = 42, labelId = 1))
                db.MessageRemindersDao().insert(MessageReminder(messageId = 9, isMMS = false, threadId = 4,
                    remindAt = 6, snapshotBody = "backup"))
            }
        }
        val backup = MessagesDatabase.downgradeBackup(context, LIVE, MessagesDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER), backup)

        withDb(LIVE) { db ->
            blocking {
                val labels = db.LabelsDao().getLabels()
                assertThat(labels.map { it.name }).containsExactly("Work", "Family")
                val family = labels.first { it.name == "Family" }
                assertThat(db.LabelsDao().getLinks()).containsExactly(ConversationLabel(42, family.id))
                assertThat(db.MessageRemindersDao().getAll().map { it.snapshotBody })
                    .containsExactly("live", "backup")
            }
        }
    }

    private fun withDb(name: String, block: (MessagesDatabase) -> Unit) {
        val db = MessagesDatabase.build(context, name)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun <T> blocking(block: suspend () -> T): T = offMainThread { runBlocking { block() } }

    private companion object {
        const val LIVE = "label-recovery-live.db"
        const val OTHER = "label-recovery-other.db"
    }
}
