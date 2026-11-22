package com.glooboo.umessage.testing

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.DefaultSmsRole
import org.junit.Assume.assumeTrue

/**
 * Shared steps of the instrumented tests of step J10 (spec 12.2). They run on emulator-5554 only: they write to the
 * phone's message store and take the SMS role. Made-up numbers only (the 555 range).
 */
object DeviceTest {

    /** The app under test (the debug package); the instrumentation runs in its process. */
    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    /** Every J10 test stops here on anything but an emulator. */
    fun assumeEmulator() {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emu") ||
            Build.MODEL.contains("sdk")
        assumeTrue("Runs on the emulator only", emulator)
    }

    /** One shell command as the shell user (as `adb shell` would run it); its output. */
    fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

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
     * Removes the chats of [addresses] from the phone's store and the app's caches (a run that failed half way leaves
     * them). Needs the SMS role; does nothing without it. Never throws.
     */
    fun forgetChats(addresses: Collection<String>) {
        if (!SmsRole.isHeld()) return
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
 * revoked"; the "don't kill" flag does not stop it). The uninstall at the end of the instrumented run gives the role
 * back to the app that held it before. No J10 class needs the role to be absent.
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
