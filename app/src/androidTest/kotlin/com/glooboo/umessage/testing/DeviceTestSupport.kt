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
 * phone's message store and take the SMS role for a while. Made-up numbers only (the 555 range).
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
 * through UiAutomation's shell. [take] remembers the holder before; [Taken.restore] gives the role back to it. Both
 * pass the flag "don't kill the app" (RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP = 1): the app's process is the
 * test's process.
 */
object SmsRole {
    private const val ROLE = "android.app.role.SMS"
    private const val DONT_KILL_APP = 1

    /** What [take] found and did. */
    class Taken internal constructor(private val previous: String, private val changed: Boolean) {
        /** Gives the role back to the app that held it before (or to no app). Never throws. */
        fun restore() {
            if (!changed) return
            val pkg = DeviceTest.context.packageName
            runCatching {
                if (previous.isNotEmpty() && previous != pkg) {
                    DeviceTest.shell("cmd role add-role-holder $ROLE $previous $DONT_KILL_APP")
                } else {
                    DeviceTest.shell("cmd role remove-role-holder $ROLE $pkg $DONT_KILL_APP")
                }
            }
        }
    }

    fun isHeld(): Boolean =
        runCatching { DefaultSmsRole.status(DeviceTest.context) == DefaultSmsRole.Status.Held }.getOrDefault(false)

    /** Makes the app the default SMS app; check [isHeld] afterwards, the grant may be refused. */
    fun take(): Taken {
        if (isHeld()) return Taken(previous = "", changed = false)
        val previous = runCatching { holder() }.getOrDefault("")
        val pkg = DeviceTest.context.packageName
        runCatching { DeviceTest.shell("cmd role add-role-holder $ROLE $pkg $DONT_KILL_APP") }
        DeviceTest.waitFor(timeoutMs = 5_000L) { isHeld() }
        return Taken(previous, changed = true)
    }

    /** The current holder's package, empty for none. `get-role-holders` prints them separated by ';'. */
    private fun holder(): String =
        DeviceTest.shell("cmd role get-role-holders $ROLE").trim().split(';', '\n').firstOrNull()?.trim().orEmpty()
}
