package com.glooboo.umessage.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.ContactsContract.PhoneLookup
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Shared steps of the instrumented tests of step J10 (spec 12.2). They run on emulator-5554 only: they write to the
 * phone's message store and take the SMS role. Made-up numbers only (the 555 range).
 */
object DeviceTest {

    /** The app under test (the debug package); the instrumentation runs in its process. */
    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    /**
     * Every J10 test stops here on anything but an emulator: the emulator's hardware (ranchu, goldfish) or the
     * `ro.kernel.qemu` flag, and also an emulator build fingerprint or model.
     */
    fun assumeEmulator() {
        assumeTrue("Runs on the emulator only", isEmulator())
    }

    /** The check of [assumeEmulator] as a plain answer, for code that must also do nothing on a phone (a tearDown). */
    fun isEmulator(): Boolean {
        val hardware = Build.HARDWARE in setOf("ranchu", "goldfish") ||
            runCatching { shell("getprop ro.kernel.qemu").trim() == "1" }.getOrDefault(false)
        val build = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emu") ||
            Build.MODEL.contains("sdk")
        return hardware && build
    }

    /** One shell command as the shell user (as `adb shell` would run it); its output. */
    fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    /**
     * True once the app's start-up steps (App.onCreate's one background thread, which also starts sorting) are over:
     * no thread runs code of the App class any more. Waits at most [timeoutMs].
     */
    fun waitForAppStart(timeoutMs: Long = 120_000L): Boolean = waitFor(timeoutMs) {
        Thread.getAllStackTraces().values.none { frames ->
            frames.any { it.className == APP_CLASS || it.className.startsWith("$APP_CLASS$") }
        }
    }

    private const val APP_CLASS = "com.glooboo.umessage.App"

    /** True once [check] holds, polled every 100 ms for at most [timeoutMs]. */
    fun waitFor(timeoutMs: Long = 10_000L, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (runCatching(check).getOrDefault(false)) return true
            if (SystemClock.uptimeMillis() >= end) return false
            SystemClock.sleep(100L)
        }
    }

    /**
     * The test's senders must be unknown to the app: no allow rule (number or prefix), no member of an allow group
     * and, when contacts can be read, no contact. Otherwise the test stops (it would judge someone else's sender).
     */
    fun assumeUnknownSenders(addresses: Collection<String>) {
        val app = context
        val allows = SpamEngine.repository(app).currentRules().filter { it.kind == ListKind.ALLOW }
        val members = SpamDatabase.getInstance(app).spamDao().getMembers()
        val contactsReadable =
            app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        for (address in addresses) {
            val ruled = allows.any { rule ->
                when (rule.type) {
                    RuleType.NUMBER -> digits(rule.pattern) == digits(address)
                    RuleType.PREFIX ->
                        digits(rule.pattern).isNotEmpty() && digits(address).startsWith(digits(rule.pattern))
                    else -> false
                }
            }
            assumeTrue("A test sender has an allow rule", !ruled)
            assumeTrue("A test sender is in an allow group", members.none { digits(it.address) == digits(address) })
            if (contactsReadable) assumeTrue("A test sender is a contact", !isContact(address))
        }
    }

    private fun digits(value: String) = value.filter(Char::isDigit)

    private fun isContact(address: String): Boolean {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return context.contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.count > 0 }
            ?: false
    }

    /**
     * Removes the chats of [addresses] from the phone's store and the app's caches (a run that failed half way leaves
     * them). Needs the SMS role and an emulator; does nothing without them (never on a phone). Never throws.
     */
    fun forgetChats(addresses: Collection<String>) {
        if (!isEmulator() || !SmsRole.isHeld()) return
        val app = context
        addresses.forEach { address ->
            runCatching {
                val threadId = app.getThreadId(address)
                if (threadId > 0L) {
                    app.deleteConversation(threadId, isDefault = true)
                    app.conversationsDB.deleteSpamThread(threadId)
                }
            }
        }
    }
}

/**
 * The SMS role for the app under test, the way the step F emulator run gave it (`cmd role add-role-holder`), here
 * through UiAutomation's shell. It is never given back during the run: taking the role away revokes the SMS
 * permissions, and Android then kills the app's process, which is the test's process (J10 emulator run: "permissions
 * revoked"; the "don't kill" flag does not stop it). The uninstall at the end of the instrumented run hands the role
 * to the system's default holder (on the emulator Google Messages), which need not be the app that held it before.
 * No J10 class needs the role to be absent.
 */
object SmsRole {
    private const val ROLE = "android.app.role.SMS"

    fun isHeld(): Boolean =
        runCatching { DefaultSmsRole.status(DeviceTest.context) == DefaultSmsRole.Status.Held }.getOrDefault(false)

    /** Makes the app the default SMS app unless it is already; check [isHeld] afterwards, the grant may be refused. */
    fun take() {
        if (isHeld()) return
        runCatching { DeviceTest.shell("cmd role add-role-holder $ROLE ${DeviceTest.context.packageName}") }
        DeviceTest.waitFor(timeoutMs = 5_000L) { isHeld() }
    }
}

/**
 * Forwarding is stopped while a test receives a text: "pause all forwarding" is switched on (and back to what it was
 * after), and the test stops when any forward rule is on, so no test can send an SMS.
 */
class NoForwarding {
    private var wasPaused: Boolean? = null

    fun start() {
        val app = DeviceTest.context
        val enabled = SpamDatabase.getInstance(app).forwardDao().getAll().any { it.enabled }
        assumeTrue("A forward rule is on", !enabled)
        val prefs = NavPrefs.get(app)
        wasPaused = prefs.forwardPaused
        prefs.forwardPaused = true
    }

    fun end() {
        wasPaused?.let { NavPrefs.get(DeviceTest.context).forwardPaused = it }
        wasPaused = null
    }
}

/** Rules a test adds; [removeAdded] deletes only those that did not exist before the test. */
class TestRules {
    private val added = mutableListOf<Long>()

    fun add(kind: ListKind, address: String) {
        val app = DeviceTest.context
        val before = SpamDatabase.getInstance(app).spamDao().getRules().mapTo(HashSet()) { it.id }
        val id = runBlocking {
            SpamEngine.repository(app).addRule(SpamRule(kind = kind, type = RuleType.NUMBER, pattern = address))
        }
        if (id > 0L && id !in before) added += id
    }

    fun removeAdded() {
        if (added.isEmpty()) return
        runCatching { runBlocking { SpamEngine.repository(DeviceTest.context).removeRules(added.toList()) } }
        added.clear()
    }
}
