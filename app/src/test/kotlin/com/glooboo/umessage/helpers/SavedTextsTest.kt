package com.glooboo.umessage.helpers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** N06 and I10: the saved texts list, and the quick replies a notification offers from it. */
@RunWith(AndroidJUnit4::class)
class SavedTextsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: SavedTexts

    @Before
    fun setUp() {
        app.composerPrefs.savedTextsJson = ""
        store = SavedTexts(app.composerPrefs)
    }

    @Test
    fun startsEmptyAndKeepsTheOrderTextsWereAdded() {
        assertThat(store.all()).isEmpty()
        store.add("Home", "12 Example Street, Sampletown")
        store.add("", "On my way")
        assertThat(store.all().map { it.title }).containsExactly("Home", "On my way").inOrder()
        assertThat(store.all().map { it.text }).containsExactly("12 Example Street, Sampletown", "On my way").inOrder()
    }

    @Test
    fun theLimitIsTwentyAndABlankOrRepeatedTextIsRefused() {
        repeat(SavedTexts.MAX_COUNT) { assertThat(store.add("t$it", "text $it")).isEqualTo(SavedTextAdd.Added) }
        assertThat(store.add("more", "one more")).isEqualTo(SavedTextAdd.Full)
        assertThat(store.all()).hasSize(SavedTexts.MAX_COUNT)
        assertThat(store.add("again", "text 3")).isEqualTo(SavedTextAdd.Duplicate)
        store.delete(store.all().first().id)
        assertThat(store.add("", "   ")).isEqualTo(SavedTextAdd.Empty)
        assertThat(store.add("new", "fresh text")).isEqualTo(SavedTextAdd.Added)
    }

    @Test
    fun editAndDeleteChangeOnlyThatEntry() {
        store.add("A", "first")
        store.add("B", "second")
        val first = store.all().first()

        assertThat(store.update(first.id, "A2", "first, changed")).isTrue()
        assertThat(store.all().map { it.title to it.text }).containsExactly("A2" to "first, changed", "B" to "second")
        assertThat(store.update("missing", "x", "y")).isFalse()
        assertThat(store.update(first.id, "A2", "  ")).isFalse()

        store.delete(first.id)
        assertThat(store.all().map { it.title }).containsExactly("B")
    }

    @Test
    fun aMissingTitleIsTheStartOfTheFirstLine() {
        store.add("", "Card for refunds\nnot a real number")
        assertThat(store.all().single().title).isEqualTo("Card for refunds")
    }

    @Test
    fun corruptStoredDataReadsAsEmpty() {
        app.composerPrefs.savedTextsJson = "{not json"
        assertThat(store.all()).isEmpty()
        app.composerPrefs.savedTextsJson = """[{"id":"1","text":"ok","title":"t"},{"id":"2"},5]"""
        assertThat(store.all().map { it.id }).containsExactly("1")
    }

    @Test
    fun quickRepliesAreShortFewAndNeverHoldALongNumber() {
        store.add("Short", "Call you later")
        store.add("Long", "This text is much longer than forty characters in total")
        store.add("Card", "Card 4000123456789010")
        repeat(6) { store.add("n$it", "ok $it") }

        val replies = store.quickReplies().map { it.text }

        assertThat(replies).hasSize(SavedTexts.QUICK_REPLY_MAX_COUNT)
        assertThat(replies.first()).isEqualTo("Call you later")
        assertThat(replies).doesNotContain("Card 4000123456789010")
    }

    @Test
    fun notificationChoicesAreAbsentWithoutSavedTextsAndPresentWithThem() {
        assertThat(NotificationQuickReplies.choices(app)).isNull()
        store.add("", "On my way")
        assertThat(NotificationQuickReplies.choices(app)!!.map { it.toString() }).containsExactly("On my way")
    }
}
