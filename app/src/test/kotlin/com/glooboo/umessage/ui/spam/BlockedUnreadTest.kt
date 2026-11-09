package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.conversations.unreadPreviewWeight
import com.glooboo.umessage.ui.conversations.unreadTitleWeight
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Unread held chats are bold on the Blocked tab, exactly as unread chats are on the Messages tab. */
@RunWith(AndroidJUnit4::class)
class BlockedUnreadTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun message(id: Long, at: Long, unread: Boolean, address: String = SENDER) =
        BlockedMessage(id, address, "text $id", 1, false, at, DecisionReason.UNKNOWN_SENDER, unread = unread)

    @Test
    fun aSenderWithAnUnreadMessageIsUnreadAndCountsThem() {
        val group = groupBySender(listOf(message(1, 3, true), message(2, 2, false), message(3, 1, true))).single()
        assertThat(group.unread).isTrue()
        assertThat(group.unreadCount).isEqualTo(2)
    }

    @Test
    fun aSenderWithEveryMessageReadIsNotUnread() {
        val group = groupBySender(listOf(message(1, 2, false), message(2, 1, false))).single()
        assertThat(group.unread).isFalse()
        assertThat(group.unreadCount).isEqualTo(0)
    }

    @Test
    fun anUnreadChatIsBoldOnBothTabsAndAReadOneIsNot() {
        assertThat(unreadTitleWeight(true)).isEqualTo(FontWeight.Bold)
        assertThat(unreadPreviewWeight(true)).isEqualTo(FontWeight.SemiBold)
        assertThat(unreadTitleWeight(false)).isEqualTo(FontWeight.Normal)
        assertThat(unreadPreviewWeight(false)).isEqualTo(FontWeight.Normal)
    }

    @Test
    fun markingASenderReadClearsItsRowAndLeavesOthersUnread() {
        val repo = FakeSpamRepository(
            initialBlocked = listOf(
                message(1, 3, true), message(2, 2, true), message(3, 1, true, address = "+447000000000"),
            ),
            initialRules = emptyList(),
        )
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        try {
            val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
            scope.launch { vm.state.collect { } }
            awaitUntil { !vm.state.value.loading }
            assertThat(vm.state.value.groups.map { it.unread }).containsExactly(true, true)

            vm.markRead(SENDER)
            awaitUntil { vm.state.value.groups.first { it.hasAddress(SENDER) }.unreadCount == 0 }

            val groups = vm.state.value.groups
            assertThat(groups.first { it.hasAddress(SENDER) }.unread).isFalse()
            assertThat(groups.first { it.hasAddress("+447000000000") }.unread).isTrue()
        } finally {
            scope.cancel()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+447121234567"
    }
}
