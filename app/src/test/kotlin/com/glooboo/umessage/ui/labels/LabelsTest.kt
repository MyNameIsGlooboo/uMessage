package com.glooboo.umessage.ui.labels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I29: the rules for label names and the database side of labels. Thread ids are invented. */
@RunWith(AndroidJUnit4::class)
class LabelsTest {

    private lateinit var db: MessagesDatabase
    private lateinit var repository: LabelsRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        repository = LabelsRepository(db.LabelsDao())
    }

    @After
    fun tearDown() = db.close()

    private fun <T> blocking(block: suspend () -> T): T = offMainThread { runBlocking { block() } }

    private fun labels() = blocking { db.LabelsDao().getLabels() }

    private fun threads(name: String) = blocking { db.LabelsDao().getThreadIds(labels().first { it.name == name }.id) }

    private fun label(id: Long, name: String) = Label(id = id, name = name, color = 0, sortOrder = id.toInt())

    @Test
    fun namesAreTrimmedCollapsedAndShortened() {
        assertThat(LabelRules.cleanName("  Bank   alerts ")).isEqualTo("Bank alerts")
        assertThat(LabelRules.cleanName("x".repeat(50))).hasLength(LabelRules.MAX_NAME_LENGTH)
    }

    @Test
    fun aNameMustBeNewAndNotEmpty() {
        val existing = listOf(label(1, "Banks"))
        assertThat(LabelRules.check(existing, "  ")).isEqualTo(LabelResult.Empty)
        assertThat(LabelRules.check(existing, "banks")).isEqualTo(LabelResult.Duplicate)
        assertThat(LabelRules.check(existing, "Family")).isEqualTo(LabelResult.Ok)
        assertThat(LabelRules.check(existing, "banks", exceptId = 1)).isEqualTo(LabelResult.Ok)
    }

    @Test
    fun theNumberOfLabelsIsLimited() {
        val full = (1..LabelRules.MAX_LABELS).map { label(it.toLong(), "L$it") }
        assertThat(LabelRules.check(full, "One more")).isEqualTo(LabelResult.TooMany)
        assertThat(LabelRules.check(full, "L1x", exceptId = 1)).isEqualTo(LabelResult.Ok)
    }

    @Test
    fun colorsAreUsedUpBeforeTheyRepeat() {
        val used = LabelColor.entries.take(2).mapIndexed { i, c -> Label(i + 1L, "L$i", c.color, i) }
        assertThat(LabelRules.nextColor(used)).isEqualTo(LabelColor.entries[2].color)
        val all = LabelColor.entries.mapIndexed { i, c -> Label(i + 1L, "L$i", c.color, i) }
        assertThat(LabelRules.nextColor(all)).isEqualTo(LabelColor.entries[0].color)
    }

    @Test
    fun addingKeepsTheOrderAndFilesTheConversations() {
        val first = blocking { repository.add("Banks", LabelColor.Blue.color, listOf(7L, 8L)) }
        assertThat(first).isEqualTo(LabelResult.Ok)
        assertThat(blocking { repository.add("Family", LabelColor.Green.color) }).isEqualTo(LabelResult.Ok)

        assertThat(labels().map { it.name }).containsExactly("Banks", "Family").inOrder()
        assertThat(threads("Banks")).containsExactly(7L, 8L)
        assertThat(threads("Family")).isEmpty()
    }

    @Test
    fun aBadNameWritesNothing() {
        blocking { repository.add("Banks", 1) }
        assertThat(blocking { repository.add("BANKS", 2) }).isEqualTo(LabelResult.Duplicate)
        assertThat(blocking { repository.add("", 2) }).isEqualTo(LabelResult.Empty)
        assertThat(labels()).hasSize(1)
    }

    @Test
    fun noMoreLabelsThanTheLimit() {
        repeat(LabelRules.MAX_LABELS) { blocking { repository.add("L$it", 1) } }
        assertThat(blocking { repository.add("Extra", 1) }).isEqualTo(LabelResult.TooMany)
        assertThat(labels()).hasSize(LabelRules.MAX_LABELS)
    }

    @Test
    fun renamingChangesNameAndColorOnly() {
        blocking { repository.add("Banks", 1, listOf(7L)) }
        val id = labels().single().id

        assertThat(blocking { repository.update(id, "Money", 5) }).isEqualTo(LabelResult.Ok)

        val renamed = labels().single()
        assertThat(renamed.name).isEqualTo("Money")
        assertThat(renamed.color).isEqualTo(5)
        assertThat(threads("Money")).containsExactly(7L)
    }

    @Test
    fun assigningAndUnassigningManyConversations() {
        blocking { repository.add("Banks", 1) }
        blocking { repository.add("Work", 2) }
        val banks = labels().first { it.name == "Banks" }.id

        blocking { repository.setAssigned(listOf(1L, 2L, 3L), banks, true) }
        blocking { repository.setAssigned(listOf(2L), labels().first { it.name == "Work" }.id, true) }
        blocking { repository.setAssigned(listOf(2L), banks, false) }

        assertThat(threads("Banks")).containsExactly(1L, 3L)
        assertThat(threads("Work")).containsExactly(2L)
        assertThat(blocking { db.LabelsDao().getLabelsOfConversation(2L) }.map { it.name }).containsExactly("Work")
    }

    @Test
    fun filingTwiceIsHarmless() {
        blocking { repository.add("Banks", 1, listOf(7L)) }
        val id = labels().single().id
        blocking { repository.setAssigned(listOf(7L), id, true) }
        assertThat(threads("Banks")).containsExactly(7L)
    }

    @Test
    fun deletingALabelKeepsTheOtherLabelsAndTheirConversations() {
        blocking { repository.add("Banks", 1, listOf(7L, 8L)) }
        blocking { repository.add("Work", 2, listOf(8L)) }
        val banks = labels().first { it.name == "Banks" }.id

        blocking { repository.delete(banks) }

        assertThat(labels().map { it.name }).containsExactly("Work")
        assertThat(threads("Work")).containsExactly(8L)
        assertThat(blocking { db.LabelsDao().getThreadIds(banks) }).isEmpty()
        assertThat(blocking { db.LabelsDao().getLabelsOfConversation(7L) }).isEmpty()
    }

    @Test
    fun deletingAConversationTakesItOutOfEveryLabel() {
        blocking { repository.add("Banks", 1, listOf(7L, 8L)) }
        blocking { repository.add("Work", 2, listOf(7L)) }

        blocking { db.LabelsDao().removeAllFromConversation(7L) }

        assertThat(threads("Banks")).containsExactly(8L)
        assertThat(threads("Work")).isEmpty()
        assertThat(labels()).hasSize(2)
    }

    @Test
    fun theStateAnswersWhichConversationsCarryALabel() {
        val state = LabelsUiState.of(
            labels = listOf(label(1, "Banks"), label(2, "Work")),
            links = listOf(ConversationLabel(7, 1), ConversationLabel(8, 1), ConversationLabel(8, 2)),
        )

        assertThat(state.loaded).isTrue()
        assertThat(state.threadsOf(1)).containsExactly(7L, 8L)
        assertThat(state.threadsOf(99)).isEmpty()
        assertThat(state.label(2)?.name).isEqualTo("Work")
        assertThat(state.coverage(1, listOf(7L, 8L))).isEqualTo(LabelCoverage.All)
        assertThat(state.coverage(2, listOf(7L, 8L))).isEqualTo(LabelCoverage.Some)
        assertThat(state.coverage(2, listOf(7L))).isEqualTo(LabelCoverage.None)
    }
}
