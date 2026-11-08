package com.glooboo.umessage.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** B14: counts are plurals; the new 0.4.2 strings exist in every language the app ships. */
@RunWith(AndroidJUnit4::class)
class UiPartAStringsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `one item reads as singular and several as plural`() {
        val r = context.resources
        assertEquals("1 digit entered", r.getQuantityString(R.plurals.dialogs_lock_pin_entered, 1, 1))
        assertEquals("4 digits entered", r.getQuantityString(R.plurals.dialogs_lock_pin_entered, 4, 4))
        assertEquals("Backup saved: 1 item", r.getQuantityString(R.plurals.w3c_backup_saved, 1, 1))
        assertEquals("Backup saved: 3 items", r.getQuantityString(R.plurals.w3c_backup_saved, 3, 3))
        assertTrue(r.getQuantityString(R.plurals.w3a_forward_limit_text, 1, 1).startsWith("Limit of 1 forward reached"))
        assertTrue(
            r.getQuantityString(R.plurals.w3a_forward_limit_text, 5, 5).startsWith("Limit of 5 forwards reached"),
        )
        assertEquals(
            "Copied 1 message. 0 already in your conversations.",
            r.getQuantityString(R.plurals.sim_messages_done, 1, 1, 0),
        )
        assertTrue(r.getQuantityString(R.plurals.sim_messages_done_failed, 2, 2, 1, 1).startsWith("Copied 2 messages."))
    }

    @Test
    fun `the first welcome card no longer names the three modes`() {
        // I13: Strict, Smart and Off are explained on the unknown-senders step, not on the first screen.
        assertEquals("Unknown senders wait in Spam, never lost.", context.getString(R.string.onb_welcome_spam_desc))
    }

    @Test
    @Config(qualifiers = "fa")
    fun `Persian has the plurals too`() {
        val saved = context.resources.getQuantityString(R.plurals.w3c_backup_saved, 1, 1)
        // Persian formats the number with Persian digits.
        assertTrue(saved.contains("1") || saved.contains("۱"))
        assertEquals("اختیاری", context.getString(R.string.onb_allow_optional))
    }

    @Test
    fun `every language has the new strings and plurals, with the quantities its grammar needs`() {
        val res = File("src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .filter { File(it, "strings_onboarding.xml").exists() }
        assertEquals(21, folders.size)
        val keys = listOf(
            "onb_allow_optional", "backup_drive_hint", "move_from_app_title", "move_from_app_row_body",
            "move_from_app_intro", "move_from_app_step1", "move_from_app_step2", "move_from_app_step3",
            "move_from_app_format", "sim_messages_done", "sim_messages_done_failed", "w3c_backup_saved",
            "dialogs_lock_pin_entered", "w3a_forward_limit_text",
        )
        folders.forEach { folder ->
            val file = File(folder, "strings_uia_042.xml")
            assertTrue("${folder.name}: strings_uia_042.xml missing", file.exists())
            val text = file.readText()
            keys.forEach { assertTrue("${folder.name}: $it missing", text.contains("name=\"$it\"")) }
            val other = "quantity=\"other\""
            assertTrue("${folder.name}: no quantity other", text.contains(other))
            val one = text.contains("quantity=\"one\"")
            val oneLess = setOf(
                "values-in", "values-ja", "values-ko", "values-th", "values-vi", "values-zh-rCN", "values-zh-rTW",
            )
            assertEquals("${folder.name}: quantity one", folder.name !in oneLess, one)
        }
    }

    @Test
    fun `the plain strings that became plurals are gone from every locale`() {
        val gone = listOf(
            "sim_messages_done", "sim_messages_done_failed", "w3c_backup_saved", "dialogs_lock_pin_entered",
            "w3a_forward_limit_text",
        )
        val folders = File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        folders.forEach { folder ->
            folder.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().forEach { file ->
                val text = file.readText()
                gone.forEach {
                    val tag = "<string name=\"$it\">"
                    assertFalse("${file.path}: $tag still there", text.contains(tag))
                }
            }
        }
    }
}
