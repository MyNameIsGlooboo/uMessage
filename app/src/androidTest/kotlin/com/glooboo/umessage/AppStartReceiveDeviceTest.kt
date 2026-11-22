package com.glooboo.umessage

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.activities.MainActivity
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.receivers.SmsReceiver
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.DeviceTest
import com.glooboo.umessage.testing.SmsRole
import com.glooboo.umessage.ui.onboarding.OnboardingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Spec 12.2 / M-T5: the app starts and receives a text. MainActivity is launched; then two incoming SMS go the app's
 * receiver path ([SmsReceiver.handle] with an SMS_DELIVER intent and a 3GPP PDU, as Android delivers it; nothing is
 * sent, no `adb emu sms`). The one from an allowed sender is stored unread in its thread, listed in Messages and shown
 * on the main screen; the one from a blocked sender is stored read, silent and listed in Spam only.
 *
 * Instrumented tests run on the debug build (`connectedFossDebugAndroidTest`); the minified daily build cannot be the
 * instrumented target, so M-T5 on the daily build stays a manual check (plan 4.4). Needs the SMS role (taken for the
 * test and kept until the uninstall). Emulator only. Both senders are made up.
 */
@RunWith(AndroidJUnit4::class)
class AppStartReceiveDeviceTest {

    private val app = DeviceTest.context
    private var onboardingDone = false
    private val ruleIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        DeviceTest.assumeEmulator()
        SmsRole.take()
        assumeTrue("uMessage must be the default SMS app to write to the store", SmsRole.isHeld())
        DeviceTest.forgetChats(SENDERS)
        // Past the Welcome pages, as after a finished onboarding; given back after the test.
        onboardingDone = OnboardingState.isCompleted(app)
        OnboardingState.setCompleted(app, true)
        // The contacts and notification requests of the main screen would open system dialogs over the list. These
        // are the app's own permissions, not device settings; a revoke would kill the test's process, and the
        // uninstall at the end of the instrumented run removes them.
        grantIfMissing(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantIfMissing(Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking {
            val repository = SpamEngine.repository(app)
            ruleIds += repository.addRule(SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = ALLOWED))
            ruleIds += repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        }
    }

    @After
    fun tearDown() {
        DeviceTest.forgetChats(SENDERS)
        runCatching { runBlocking { SpamEngine.repository(app).removeRules(ruleIds) } }
        runCatching { app.getSystemService(NotificationManager::class.java).cancelAll() }
        OnboardingState.setCompleted(app, onboardingDone)
    }

    private fun grantIfMissing(permission: String) {
        if (app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        DeviceTest.shell("pm grant ${app.packageName} $permission")
    }

    @Test
    fun theAppStartsAndReceivesATextIntoMessagesAndASpamTextIntoSpam() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val nonce = System.currentTimeMillis() % 100_000
            val hello = "J10 hello $nonce"
            val advert = "J10 advert $nonce"

            SmsReceiver().handle(app, deliverIntent(ALLOWED, hello), housekeeping = false)
            SmsReceiver().handle(app, deliverIntent(BLOCKED, advert), housekeeping = false)

            val dao = app.conversationsDB
            val helloRow = storedRow(ALLOWED, hello)
            assertEquals("allowed text: unread", 0, helloRow.read)
            assertEquals("allowed text: own thread", Threads.getOrCreateThreadId(app, ALLOWED), helloRow.thread)
            assertTrue(
                "allowed text: listed in Messages",
                DeviceTest.waitFor { dao.getMessagesList().any { it.threadId == helloRow.thread } },
            )
            assertFalse("allowed text: not in Spam", dao.isInSpam(helloRow.thread))
            assertTrue("allowed text: on the main screen", DeviceTest.waitFor(timeoutMs = 20_000L) { shows(hello) })

            val advertRow = storedRow(BLOCKED, advert)
            assertEquals("spam text: read", 1, advertRow.read)
            assertEquals("spam text: seen", 1, advertRow.seen)
            assertEquals("spam text: own thread", Threads.getOrCreateThreadId(app, BLOCKED), advertRow.thread)
            assertTrue(
                "spam text: listed in Spam",
                DeviceTest.waitFor { dao.getSpamList().any { it.threadId == advertRow.thread } },
            )
            assertFalse("spam text: not in Messages", dao.getMessagesList().any { it.threadId == advertRow.thread })
        }
    }

    /** The one inbox row of [address] with [body]: read, seen, thread. */
    private fun storedRow(address: String, body: String): Stored {
        val rows = app.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.READ, Sms.SEEN, Sms.THREAD_ID),
            "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ? AND ${Sms.TYPE} = ?",
            arrayOf(address, body, "${Sms.MESSAGE_TYPE_INBOX}"),
            null,
        )!!.use { c -> buildList { while (c.moveToNext()) add(Stored(c.getInt(0), c.getInt(1), c.getLong(2))) } }
        assertEquals("stored once", 1, rows.size)
        return rows.single()
    }

    private data class Stored(val read: Int, val seen: Int, val thread: Long)

    /** Whether a window of the app (not the shade, not a heads-up) shows [text]. */
    private fun shows(text: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
        return roots.filter { it.packageName == app.packageName }.any { contains(it, text) }
    }

    private fun contains(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return true
        return (0 until node.childCount).any { i -> node.getChild(i)?.let { contains(it, text) } == true }
    }

    /** An SMS_DELIVER broadcast as Android sends it to the default SMS app: one 3GPP PDU. */
    private fun deliverIntent(sender: String, body: String): Intent =
        Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
            .putExtra("pdus", arrayOf<Any>(DeliverPdu.make(sender, body, System.currentTimeMillis() - 60_000L)))
            .putExtra("format", "3gpp")

    private companion object {
        const val ALLOWED = "+15550001235"
        const val BLOCKED = "+15550001234"
        val SENDERS = listOf(ALLOWED, BLOCKED)
    }
}

/**
 * A 3GPP SMS-DELIVER PDU (TS 23.040): no SMSC, an international sender, UCS-2 text (at most 70 characters) and the
 * service centre time [sentAt] in UTC.
 */
internal object DeliverPdu {
    fun make(sender: String, body: String, sentAt: Long): ByteArray {
        require(body.length <= 70) { "one PDU holds 70 UCS-2 characters" }
        val digits = sender.removePrefix("+")
        val out = ByteArrayOutputStream()
        out.write(0x00) // no SMSC address
        out.write(0x04) // SMS-DELIVER, no more messages to send
        out.write(digits.length)
        out.write(0x91) // international number
        out.write(semiOctets(digits))
        out.write(0x00) // protocol identifier
        out.write(0x08) // UCS-2
        out.write(timestamp(sentAt))
        val text = body.toByteArray(Charsets.UTF_16BE)
        out.write(text.size)
        out.write(text)
        return out.toByteArray()
    }

    /** Digits two to a byte, low nibble first, an odd count padded with F. */
    private fun semiOctets(digits: String): ByteArray = digits.padEnd(digits.length + digits.length % 2, 'F')
        .chunked(2)
        .map { pair -> ((pair[1].digitToInt(16) shl 4) or pair[0].digitToInt(16)).toByte() }
        .toByteArray()

    /** Year, month, day, hour, minute, second and a zone of 0 (UTC), each as two swapped digits. */
    private fun timestamp(at: Long): ByteArray {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = at }
        val fields = listOf(
            utc.get(Calendar.YEAR) % 100, utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH),
            utc.get(Calendar.HOUR_OF_DAY), utc.get(Calendar.MINUTE), utc.get(Calendar.SECOND), 0,
        )
        return fields.map { (((it % 10) shl 4) or (it / 10)).toByte() }.toByteArray()
    }
}
