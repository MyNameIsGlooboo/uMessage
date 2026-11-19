package com.glooboo.umessage.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.BackupCrypto
import com.glooboo.umessage.helpers.MessagesImporter
import com.glooboo.umessage.helpers.MessagesWriter
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Golden backup files: fixed files under `src/test/resources/golden/` that must keep decoding to the same content, so a
 * change to the crypto, the folding of passwords or the readers cannot silently make old exports unreadable.
 *
 * IMPORTANT: these fixtures were produced from the 0.4.14 format code (by `golden/generate_golden.py`, an independent
 * re-implementation of BackupCrypto, BackupPasswordForms and the file formats). They are NOT files written by a
 * released device build. When a real export from a released build can be shared in invented-content form, add it next
 * to these. All content is invented; the only numbers are +15555550100 and +15555550101.
 *
 * The Persian-password files need the JVM to derive keys from UTF-8 (as Android does). On a JDK 17 whose default
 * charset is not UTF-8 (for example Windows with Cp1252) the JDK's PBKDF2 encodes the password differently, so those
 * tests are skipped there; run the tests with `-Dfile.encoding=UTF-8` (Gradle `jvmArgs`) to run them.
 */
@RunWith(AndroidJUnit4::class)
class GoldenBackupFixturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun resource(name: String): InputStream =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("golden/$name")) { "missing fixture golden/$name" }

    private fun bytes(name: String) = resource(name).use { it.readBytes() }

    /** Text as written by the generator (LF), whatever line endings a checkout gave the file. */
    private fun text(name: String) = bytes(name).decodeToString().replace("\r\n", "\n")

    private fun importer() = MessagesImporter(context, MessagesWriter(context) { emptyList() })
    private val options = MessagesImporter.Options(sms = true, mms = true)

    private fun open(name: String, password: String): String =
        BackupCrypto.decrypt(bytes(name).inputStream(), password.toCharArray()).use { it.readBytes() }
            .decodeToString().replace("\r\n", "\n")

    private fun requireUtf8Jvm() = assumeTrue(
        "JVM default charset is ${Charset.defaultCharset()}, not UTF-8: run with -Dfile.encoding=UTF-8",
        Charset.defaultCharset() == Charsets.UTF_8,
    )

    private fun importedRows(store: FakeMessageStore) =
        store.rows("sms").map { listOf(it["address"], it["body"], it["date"], it["type"]) }

    private val expectedRows = listOf(
        listOf("+15555550100", "Golden fixture one: hello from the 0.4.14 format.", 1700000001000L, 1L),
        listOf("+15555550100", PERSIAN_BODY, 1700000002000L, 2L),
        listOf("+15555550101", "Golden fixture three: second sender.", 1700000003000L, 1L),
    )

    // ---- plain files ----------------------------------------------------------------------------------------------

    @Test
    fun jsonBackup_restoresTheExpectedMessages() {
        val store = FakeMessageStore.install()

        val summary = importer().import(bytes("messages.json").inputStream(), options)

        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.damaged).isEqualTo(0)
        assertThat(importedRows(store)).containsExactlyElementsIn(expectedRows).inOrder()
    }

    @Test
    fun xmlBackup_restoresTheExpectedMessages() {
        val store = FakeMessageStore.install()

        val summary = importer().import(bytes("messages.xml").inputStream(), options)

        assertThat(summary.added).isEqualTo(3)
        assertThat(summary.damaged).isEqualTo(0)
        assertThat(importedRows(store)).containsExactlyElementsIn(expectedRows).inOrder()
    }

    @Test
    fun settingsBackup_decodesToTheExpectedContent() {
        val content = SettingsBackupFormat.decode(text("settings.json"))

        assertThat(content.appVersion).isEqualTo("0.4.14")
        assertThat(content.modeIncludesSwitch).isTrue()
        assertThat(content.rules.map { Triple(it.kind, it.type, it.pattern) }).containsExactly(
            Triple(ListKind.BLOCK, RuleType.KEYWORD, "free prize"),
            Triple(ListKind.BLOCK, RuleType.KEYWORD, "تخفیف ویژه"),
            Triple(ListKind.ALLOW, RuleType.NUMBER, "+15555550100"),
        ).inOrder()
        assertThat(content.rules.first().note).isEqualTo("golden")
        val forward = content.forwardRules.single()
        assertThat(forward.senderType).isEqualTo(RuleType.NUMBER)
        assertThat(forward.senderPattern).isEqualTo("+15555550101")
        assertThat(forward.bodyType).isEqualTo(RuleType.KEYWORD)
        assertThat(forward.bodyPattern).isEqualTo("code")
        assertThat(forward.windowStart).isEqualTo(480)
        assertThat(forward.windowEnd).isEqualTo(1200)
        assertThat(forward.targets).containsExactly("+15555550100")
        assertThat(forward.sendFrom).isEqualTo(ForwardSim.SAME)
        assertThat(forward.includeCodes).isTrue()
        assertThat(forward.enabled).isFalse()
        assertThat(content.blockedKeywords).containsExactly("lottery")
        assertThat(content.settings.spamMode).isEqualTo(UnknownSenderMode.SMART)
        assertThat(content.settings.purgeAfterDays).isEqualTo(14)
        assertThat(content.settings.notifyOnBlock).isTrue()
        assertThat(content.settings.dailyForwardLimit).isEqualTo(30)
        assertThat(content.settings.maskForwardedCodes).isTrue()
        assertThat(content.settings.sendLongAsMmsAfter).isEqualTo(3)
        assertThat(content.settings.signature).isEqualTo("golden")
        assertThat(content.settings.useJalaliDates).isNull() // not in the file: left as it is on the phone
    }

    // ---- protected files (UMSGENC1 and UMSGENC2) ------------------------------------------------------------------

    @Test
    fun theFilesCarryTheirVersionMarker() {
        listOf("v1_ascii", "v1_persian").forEach {
            assertThat(bytes("messages_$it.umsgenc").copyOf(8).decodeToString()).isEqualTo("UMSGENC1")
        }
        listOf("v2_ascii", "v2_persian").forEach {
            assertThat(bytes("messages_$it.umsgenc").copyOf(8).decodeToString()).isEqualTo("UMSGENC2")
        }
        listOf("v1_ascii", "v1_persian", "v2_ascii", "v2_persian").forEach {
            assertThat(BackupCrypto.isProtected(bytes("messages_$it.umsgenc").inputStream().buffered())).isTrue()
        }
        assertThat(BackupCrypto.isProtected(bytes("messages.json").inputStream().buffered())).isFalse()
    }

    @Test
    fun umsgenc1_withAnAsciiPassword_opensToTheOriginalJson() {
        assertThat(open("messages_v1_ascii.umsgenc", ASCII_PASSWORD)).isEqualTo(text("messages.json"))
    }

    @Test
    fun umsgenc2_withAnAsciiPassword_opensToTheOriginalJson() {
        assertThat(open("messages_v2_ascii.umsgenc", ASCII_PASSWORD)).isEqualTo(text("messages.json"))
    }

    @Test
    fun umsgenc1_withAPersianPassword_opensToTheOriginalJson() {
        requireUtf8Jvm()
        assertThat(open("messages_v1_persian.umsgenc", PERSIAN_PASSWORD)).isEqualTo(text("messages.json"))
    }

    @Test
    fun umsgenc2_withAPersianPassword_opensToTheOriginalJson() {
        requireUtf8Jvm()
        assertThat(open("messages_v2_persian.umsgenc", PERSIAN_PASSWORD)).isEqualTo(text("messages.json"))
    }

    @Test
    fun umsgenc2_opensWithThePersianPasswordTypedOnAnArabicKeyboard() {
        requireUtf8Jvm()
        // Arabic kaf and yeh and Arabic-Indic digits: the same password once folded (I8).
        assertThat(open("messages_v2_persian.umsgenc", ARABIC_TYPED_PASSWORD)).isEqualTo(text("messages.json"))
    }

    @Test
    fun umsgenc1_doesNotOpenWithTheArabicKeyboardSpelling() {
        requireUtf8Jvm()
        // The first version used the password as typed, so only the exact characters open it.
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            open("messages_v1_persian.umsgenc", ARABIC_TYPED_PASSWORD)
        }
    }

    @Test
    fun aWrongPasswordIsReported_forEveryFile() {
        listOf("v1_ascii", "v2_ascii", "v1_persian", "v2_persian").forEach {
            assertThrows(BackupCrypto.WrongPasswordException::class.java) {
                open("messages_$it.umsgenc", "not the password")
            }
        }
    }

    @Test
    fun aProtectedFile_afterOpening_restoresTheExpectedMessages() {
        val store = FakeMessageStore.install()
        val opened = BackupCrypto.decrypt(
            bytes("messages_v2_ascii.umsgenc").inputStream(), ASCII_PASSWORD.toCharArray(),
        )

        val summary = opened.use { importer().import(it, options) }

        assertThat(summary.added).isEqualTo(3)
        assertThat(importedRows(store)).containsExactlyElementsIn(expectedRows).inOrder()
    }

    private companion object {
        const val ASCII_PASSWORD = "golden-ascii-pass-1"

        // kaf, lam, yeh, dal, Persian digits one two three
        const val PERSIAN_PASSWORD = "کلید۱۲۳"

        // Arabic kaf, lam, Arabic yeh, dal, Arabic-Indic digits one two three
        const val ARABIC_TYPED_PASSWORD = "كليد١٢٣"

        const val PERSIAN_BODY =
            "سلام، این یک پیام " +
                "آزمایشی است."
    }
}
