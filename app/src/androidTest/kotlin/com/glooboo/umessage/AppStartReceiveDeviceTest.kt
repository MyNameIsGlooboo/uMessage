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
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.activities.MainActivity
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.receivers.SmsReceiver
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.testing.DeviceTest
import com.glooboo.umessage.testing.NoForwarding
import com.glooboo.umessage.testing.SmsRole
import com.glooboo.umessage.testing.TestRules
import com.glooboo.umessage.ui.main.FirstRunPagesHost
import com.glooboo.umessage.ui.onboarding.OnboardingState
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
 * sent, no `adb emu sms`). The one from an allowed sender is stored unread in its thread, listed in Messages, shown
 * on the main screen and announced (one notification for its chat); the one from a blocked sender is stored read,
 * listed in Spam only and silent (no notification for its chat). Forwarding is paused for the test and the test stops
 * when a forward rule is on, so the allowed text can never be forwarded.
 *
 * On a fresh install the end of the onboarding makes the first run's pass due, and while it is due or runs the main
 * screen shows the full progress page instead of the lists (FirstRunPagePolicy: FRESH_INSTALL). So the test starts
 * that pass and waits until it is done before it launches the app; with the step F seed it takes about a minute.
 *
 * Instrumented tests run on the debug build (`connectedFossDebugAndroidTest`); the minified daily build cannot be the
 * instrumented target, so M-T5 on the daily build stays a manual check (plan 4.4). Needs the SMS role (taken for the
 * test and kept until the uninstall). Emulator only. Both senders are made up and must be unknown to the app.
 */
@RunWith(AndroidJUnit4::class)
class AppStartReceiveDeviceTest {

    private val app = DeviceTest.context
    private var onboardingDone = false
    private val rules = TestRules()
    private val noForwarding = NoForwarding()
    private val threads = mutableListOf<Long>()

    @Before
    fun setUp() {
        DeviceTest.assumeEmulator()
        SmsRole.take()
        assumeTrue("uMessage must be the default SMS app to write to the store", SmsRole.isHeld())
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
        DeviceTest.assumeUnknownSenders(SENDERS)
        noForwarding.start()
        DeviceTest.forgetChats(SENDERS)
        rules.add(ListKind.ALLOW, ALLOWED)
        rules.add(ListKind.BLOCK, BLOCKED)
        finishFirstRun()
    }

    /**
     * The first run's pass is started as the end of the welcome flow starts it (App.checkFirstRun: the pages read the
     * onboarding again, the runner checks), and waited for.
     */
    private fun finishFirstRun() {
        (app as App).checkFirstRun()
        FirstRun.check(app)
        assertTrue(
            "the first run's pass did not finish",
            DeviceTest.waitFor(timeoutMs = FIRST_RUN_TIMEOUT_MS) {
                FirstRun.state(app).value.phase == FirstRunPhase.DONE && !firstRunPageUp()
            },
        )
    }

    /** Whether the main screen shows a first-run page (progress, or the pass is due) instead of the lists. */
    private fun firstRunPageUp(): Boolean = FirstRunPagesHost.source.value.progress.value.running

    @After
    fun tearDown() {
        // Only the notifications of the test's own chats go.
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { n -> threads.any { n.id == it.hashCode() } }
                .forEach { manager.cancel(it.tag, it.id) }
        }
        DeviceTest.forgetChats(SENDERS)
        rules.removeAdded()
        noForwarding.end()
        OnboardingState.setCompleted(app, onboardingDone)
    }

    /** Active notifications of the app for chat [threadId] (a message notification or a Spam notice). */
    private fun notificationsOf(threadId: Long) = app.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.id == threadId.hashCode() }

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
            threads += helloRow.thread
            assertEquals("allowed text: unread", 0, helloRow.read)
            assertEquals("allowed text: own thread", Threads.getOrCreateThreadId(app, ALLOWED), helloRow.thread)
            assertTrue(
                "allowed text: listed in Messages",
                DeviceTest.waitFor { dao.getMessagesList().any { it.threadId == helloRow.thread } },
            )
            assertFalse("allowed text: not in Spam", dao.isInSpam(helloRow.thread))
            // Visible at once, or further down the list: then the list is scrolled (at most 10 pages) to find it.
            var shown = DeviceTest.waitFor(timeoutMs = 20_000L) { shows(hello) }
            // Before any scroll: a list that can still scroll up is not at the top, though the newest chat should be
            // in view there (KeepNewestInView). The test never scrolls up, so that case fails and is reported.
            val atTop = listAtTop()
            var scrolls = 0
            while (!shown && scrolls < MAX_SCROLLS && scrollListDown()) {
                scrolls++
                shown = DeviceTest.waitFor(timeoutMs = 2_000L) { shows(hello) }
            }
            val listed = dao.getMessagesList()
            val where = "first-run page up: ${firstRunPageUp()}; list at top: $atTop; list position " +
                "${listed.indexOfFirst { it.threadId == helloRow.thread }} of ${listed.size}; scrolled $scrolls pages"
            if (scrolls > 0 || !shown) {
                // One line per node: logcat cuts a single long entry at about 4 KB.
                Log.d(LOG_TAG, "Main screen, $where")
                screenTree().lines().forEach { Log.d(LOG_TAG, it) }
            }
            val screen = if (shown) "" else screenTexts().take(SCREEN_TEXT_MAX)
            assertTrue("allowed text: on the main screen ($where). Screen: $screen", shown)

            val advertRow = storedRow(BLOCKED, advert)
            threads += advertRow.thread
            assertEquals("spam text: read", 1, advertRow.read)
            assertEquals("spam text: seen", 1, advertRow.seen)
            assertEquals("spam text: own thread", Threads.getOrCreateThreadId(app, BLOCKED), advertRow.thread)
            assertTrue(
                "spam text: listed in Spam",
                DeviceTest.waitFor { dao.getSpamList().any { it.threadId == advertRow.thread } },
            )
            assertFalse("spam text: not in Messages", dao.getMessagesList().any { it.threadId == advertRow.thread })

            // Announced and silent. Posts run on a background thread, so the allowed one is waited for; the blocked
            // text was received right after it, so a notification for it would be there by then too.
            assertTrue(
                "allowed text: one notification",
                DeviceTest.waitFor(timeoutMs = 15_000L) { notificationsOf(helloRow.thread).size == 1 },
            )
            assertTrue("spam text: no notification", notificationsOf(advertRow.thread).isEmpty())
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
    private fun shows(text: String): Boolean = appRoots().any { contains(it, text) }

    /** The roots of the app's own windows (no system UI, no shade, no heads-up). */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
        return roots.filter { it.packageName == app.packageName }
    }

    /** Every node of the app's windows, depth first, with its depth. */
    private fun appNodes(): List<Pair<Int, AccessibilityNodeInfo>> = buildList {
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            add(depth to node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        appRoots().forEach { walk(it, 0) }
    }

    /** The texts and content descriptions the app's windows show, in order (made-up test data only). */
    private fun screenTexts(): String = appNodes().flatMap { (_, node) ->
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
    }.filter { it.isNotBlank() }.joinToString(" | ")

    /** The node tree of the app's windows: class, text, description, scrollable. */
    private fun screenTree(): String = appNodes().joinToString("\n") { (depth, node) ->
        "  ".repeat(depth) + node.className?.toString()?.substringAfterLast('.') +
            node.text?.let { " text=\"$it\"" }.orEmpty() +
            node.contentDescription?.let { " desc=\"$it\"" }.orEmpty() +
            (if (node.isScrollable) " scrollable" else "")
    }

    /** The app's biggest scrollable node (the chat list), null when there is none. */
    private fun chatList(): AccessibilityNodeInfo? =
        appNodes().map { it.second }.filter { it.isScrollable }.maxByOrNull { it.childCount }

    /** Whether the chat list is at its top (it offers no scroll up); null when there is no list on screen. */
    private fun listAtTop(): Boolean? = chatList()?.let { list ->
        list.actionList.none { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id }
    }

    /** One page down in the app's biggest scrollable node (the chat list); false when there is none or at its end. */
    private fun scrollListDown(): Boolean =
        chatList()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

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
        const val FIRST_RUN_TIMEOUT_MS = 240_000L
        const val MAX_SCROLLS = 10
        const val SCREEN_TEXT_MAX = 1_500
        const val LOG_TAG = "uMessageTest"
        const val ALLOWED = "+15550004121"
        const val BLOCKED = "+15550004122"
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
