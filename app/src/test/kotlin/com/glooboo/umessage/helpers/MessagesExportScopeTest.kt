package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.dialogs.ExportOptionsState
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
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
