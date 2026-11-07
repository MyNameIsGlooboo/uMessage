package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.dialogs.ExportOptionsState
import com.glooboo.umessage.export.ExportScope
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Exporting a chosen set of conversations, and the choices of the export dialog. */
@RunWith(AndroidJUnit4::class)
class MessagesExportScopeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun json_exportOfChosenConversations_holdsOnlyThose() {
        val store = FakeMessageStore.install()
        val one = store.threadIdFor("+15550001")
        val two = store.threadIdFor("+15550002")
        val three = store.threadIdFor("+15550003")
        store.addSms("+15550001", "in one", 1_000, threadId = one)
        store.addSms("+15550002", "in two", 2_000, threadId = two)
        store.addSms("+15550003", "in three", 3_000, threadId = three)

        val out = ByteArrayOutputStream()
        val summary = MessagesReader(context).export(out, sms = true, mms = true, conversationIds = listOf(one, three))
        val json = out.toString(Charsets.UTF_8.name())
        assertThat(summary.sms).isEqualTo(2)
        assertThat(json).contains("in one")
        assertThat(json).contains("in three")
        assertThat(json).doesNotContain("in two")
    }

    @Test
    fun fullBackupKeepsHiddenChatsAndAChosenSetGoesThroughTheHiddenFilter() {
        val hidden = HiddenChats.get(context)
        hidden.hide(2L)
        val all = listOf(1L, 2L, 3L)
        val full = ExportScope.idsFor(context, null) { all }
        assertThat(full).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(ExportScope.idsFor(context, all) { error("not needed") })
            .containsExactlyElementsIn(ExportScope.exportable(context, all))
        hidden.unhide(2L)
    }

    @Test
    fun oneChatExportLeavesOutTheUnboundNotesOfOtherChats() {
        val store = FakeMessageStore.install()
        val one = store.threadIdFor("+15550001")
        store.addSms("+15550001", "in one", 1_000, threadId = one)
        runBlocking {
            val note = MessageNote("v1|s|r|1700000000|5550009|abc", 0, 1_700_000_000, "x", 1, 1)
            context.messageNotes.restore(listOf(note))
        }

        val part = MessagesReader(context).export(
            ByteArrayOutputStream(), sms = true, mms = true, conversationIds = listOf(one), wholePhone = false,
        )
        val whole = MessagesReader(context).export(
            ByteArrayOutputStream(), sms = true, mms = true, conversationIds = listOf(one), wholePhone = true,
        )

        assertThat(part.notes).isEqualTo(0)
        assertThat(whole.notes).isEqualTo(1)
    }

    @Test
    fun json_exportOfNoConversations_isAnEmptyArray() {
        FakeMessageStore.install().addSms("+15550001", "hello", 1_000)
        val out = ByteArrayOutputStream()
        val summary = MessagesReader(context).export(out, sms = true, mms = true, conversationIds = emptyList())
        assertThat(summary.total).isEqualTo(0)
        assertThat(out.toString(Charsets.UTF_8.name())).isEqualTo("[]")
    }

    @Test
    fun dialogChoices_pickTheFileNameTypeAndPasswordRules() {
        val state = ExportOptionsState("messages", initialSms = true, initialMms = true)
        // I14: protection is on by default and needs a password; it can be switched off.
        assertThat(state.protect).isTrue()
        assertThat(state.passwordProblem).isNotNull()
        state.protect = false
        assertThat(state.fileName).isEqualTo("messages.json")
        assertThat(state.mimeType).isEqualTo("application/json")
        assertThat(state.passwordProblem).isNull()

        state.format = ExportFormat.SMS_XML
        assertThat(state.fileName).isEqualTo("messages.xml")
        assertThat(state.mimeType).isEqualTo("text/xml")

        state.protect = true
        assertThat(state.fileName).isEqualTo("messages.umsg")
        assertThat(state.mimeType).isEqualTo("application/octet-stream")
        assertThat(state.passwordProblem).isNotNull() // nothing typed yet
        state.password = "abc"
        state.confirm = "abd"
        assertThat(state.passwordProblem).isNotNull() // the two differ
        state.confirm = "abc"
        assertThat(state.passwordProblem).isNull()
    }
}
