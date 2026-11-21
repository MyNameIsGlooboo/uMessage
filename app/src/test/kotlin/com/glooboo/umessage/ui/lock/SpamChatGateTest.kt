package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Provider truth, step C (U1): a chat in Spam counts as a locked screen while the app lock covers Spam and is locked.
 * Nothing of it shows until the lock is passed; a chat in Messages, an open lock or the flag off change nothing.
 */
@RunWith(AndroidJUnit4::class)
class SpamChatGateTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private var locked = true
    private var inSpam: (Long) -> Boolean = { true }
    private var othersCover = false
    private var passed = false
    private var revealed = 0

    /** E-3: the hints are process-wide; no test sees another's. */
    @Before
    fun setUp() = SpamChatGate.Known.clear()

    @After
    fun tearDown() = SpamChatGate.Known.clear()

    private class Screen(val controller: ActivityController<ComponentActivity>, val gate: SpamChatGate) {
        val activity: ComponentActivity get() = controller.get()
        val content: View get() = activity.findViewById(android.R.id.content)
    }

    /** The gate is made before the screen starts, as the activities make theirs (a result launcher needs that). */
    private fun open(threadId: Long = THREAD, wait: Boolean = true): Screen {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val gate = SpamChatGate(
            controller.get(), { threadId }, othersCover = { othersCover }, inSpam = { inSpam(it) }, locked = { locked },
            recentlyPassed = { passed }, onRevealed = { revealed++ },
        )
        controller.start().resume()
        return Screen(controller, gate).also {
            it.gate.check()
            if (wait) settle()
        }
    }

    /** Lets the gate's database read and its answer on the main thread run. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun lockAsked(screen: Screen): Boolean =
        shadowOf(screen.activity).nextStartedActivityForResult?.intent?.let {
            it.component?.className == LockActivity::class.java.name &&
                it.getBooleanExtra(LockActivity.EXTRA_PART, false)
        } == true

    @Test
    fun aSpamChatBehindTheLockShowsNothingAndAsksForTheLock() {
        val screen = open()

        assertThat(screen.gate.blocking).isTrue()
        assertThat(screen.content.visibility).isEqualTo(View.INVISIBLE)
        assertThat(lockAsked(screen)).isTrue()
    }

    @Test
    fun aReadThatFailsCountsAsSpam() {
        inSpam = { error("database closed") }
        val screen = open()

        assertThat(screen.gate.blocking).isTrue()
        assertThat(lockAsked(screen)).isTrue()
    }

    @Test
    fun aChatInMessagesIsNotCovered() {
        inSpam = { false }
        val screen = open()

        assertThat(screen.gate.blocking).isFalse()
        assertThat(screen.content.visibility).isEqualTo(View.VISIBLE)
        assertThat(lockAsked(screen)).isFalse()
    }

    @Test
    fun anOpenLockCoversNothing() {
        locked = false
        val screen = open()

        assertThat(screen.gate.blocking).isFalse()
        assertThat(lockAsked(screen)).isFalse()
    }

    @Test
    fun comingBackFromRecentsAfterTheLockClosedCoversAgain() {
        locked = false
        val screen = open()
        assertThat(screen.gate.blocking).isFalse()

        // The lock timed out while the chat sat in "recent apps"; it comes back to the front.
        locked = true
        screen.controller.pause().stop().restart().start().resume()
        screen.gate.check()
        settle()

        assertThat(screen.gate.blocking).isTrue()
        assertThat(screen.content.visibility).isEqualTo(View.INVISIBLE)
        assertThat(lockAsked(screen)).isTrue()
    }

    @Test
    fun passingTheLockShowsTheChat() {
        val screen = open()
        val request = shadowOf(screen.activity).nextStartedActivityForResult!!
        locked = false

        shadowOf(screen.activity).receiveResult(request.intent, Activity.RESULT_OK, null)
        settle()

        assertThat(screen.gate.blocking).isFalse()
        assertThat(screen.content.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun backFromTheLockClosesTheScreen() {
        val screen = open()
        val request = shadowOf(screen.activity).nextStartedActivityForResult!!

        shadowOf(screen.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)

        assertThat(screen.activity.isFinishing).isTrue()
        assertThat(screen.gate.blocking).isTrue()
    }

    @Test
    fun anotherGateThatCoversKeepsTheScreenHidden() {
        val screen = open()
        othersCover = true
        locked = false
        val request = shadowOf(screen.activity).nextStartedActivityForResult!!

        shadowOf(screen.activity).receiveResult(request.intent, Activity.RESULT_OK, null)

        assertThat(screen.gate.blocking).isFalse()
        assertThat(screen.content.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun withTheFlagOffNothingIsCovered() {
        ProviderTruthFlag.enabled = false
        val screen = open()

        assertThat(screen.gate.blocking).isFalse()
        assertThat(lockAsked(screen)).isFalse()
    }

    @Test
    fun aChatLastSeenInMessagesDoesNotFlashBlank() {
        inSpam = { false }
        open(threadId = OTHER_THREAD)
        // C-10: opened again, it stays visible while it is checked again.
        val again = open(threadId = OTHER_THREAD, wait = false)
        assertThat(again.content.visibility).isEqualTo(View.VISIBLE)
        settle()
        assertThat(again.gate.blocking).isFalse()
    }

    @Test
    fun aChatLastSeenInMessagesThatIsInSpamNowIsCoveredOnceKnown() {
        inSpam = { false }
        open(threadId = MOVED_THREAD)
        inSpam = { true }
        val again = open(threadId = MOVED_THREAD)
        assertThat(again.gate.blocking).isTrue()
        assertThat(again.content.visibility).isEqualTo(View.INVISIBLE)
        assertThat(lockAsked(again)).isTrue()
    }

    @Test
    fun passingTheLockTellsTheScreenItShows() {
        val screen = open()
        val request = shadowOf(screen.activity).nextStartedActivityForResult!!
        locked = false
        shadowOf(screen.activity).receiveResult(request.intent, Activity.RESULT_OK, null)

        // C-10: the screen marks the chat read then.
        assertThat(revealed).isEqualTo(1)
    }

    @Test
    fun whileAHiddenChatAsksForTheLockThisGateDoesNotAskToo() {
        othersCover = true
        val screen = open()

        assertThat(screen.gate.blocking).isTrue()
        assertThat(lockAsked(screen)).isFalse()

        // The hidden chat's lock was passed a moment ago: checked again, it counts, no second PIN.
        othersCover = false
        passed = true
        screen.gate.check()
        settle()
        assertThat(screen.gate.blocking).isFalse()
        assertThat(lockAsked(screen)).isFalse()
    }

    @Test
    fun aHintCountsForSixtySecondsAndNotFromAClockThatWentBack() {
        val known = SpamChatGate.Known
        known.note(THREAD, spam = false, now = NOTED_AT)

        assertThat(known.inSpam(THREAD, now = NOTED_AT)).isFalse()
        assertThat(known.inSpam(THREAD, now = NOTED_AT + FRESH_MS)).isFalse()
        // D-8: older than that, or noted "in the future", it counts for nothing and the chat is covered first.
        assertThat(known.inSpam(THREAD, now = NOTED_AT + FRESH_MS + 1)).isNull()
        assertThat(known.inSpam(THREAD, now = NOTED_AT - 1)).isNull()
        assertThat(known.inSpam(OTHER_THREAD, now = NOTED_AT)).isNull()
    }

    @Test
    fun whileTheReadRunsTheChatIsDecidingAndNotRevealed() {
        val release = CountDownLatch(1)
        inSpam = {
            release.await(LATCH_WAIT_S, TimeUnit.SECONDS)
            false
        }
        // A chat last seen in Messages stays visible, but D-8: nothing may mark it read before the read ends.
        SpamChatGate.Known.note(OTHER_THREAD, spam = false)
        val screen = open(threadId = OTHER_THREAD, wait = false)
        assertThat(screen.content.visibility).isEqualTo(View.VISIBLE)
        assertThat(screen.gate.deciding).isTrue()
        assertThat(revealed).isEqualTo(0)

        release.countDown()
        settle()
        assertThat(screen.gate.deciding).isFalse()
        assertThat(revealed).isEqualTo(1)
        assertThat(SpamChatGate.Known.inSpam(OTHER_THREAD)).isFalse()
    }

    @Test
    fun withTheLockOpenNothingWaitsForARead() {
        locked = false
        val screen = open(wait = false)
        assertThat(screen.gate.deciding).isFalse()
        assertThat(screen.gate.blocking).isFalse()
    }

    private companion object {
        const val THREAD = 42L
        const val OTHER_THREAD = 43L
        const val MOVED_THREAD = 44L
        const val SETTLE_ROUNDS = 20
        const val SETTLE_SLEEP_MS = 10L
        const val NOTED_AT = 1_000_000L
        const val FRESH_MS = 60_000L
        const val LATCH_WAIT_S = 5L
    }
}
