package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** TH-20: "Search in conversation" in the thread's view model. */
@RunWith(AndroidJUnit4::class)
class ThreadSearchViewModelTest {

    private fun message(id: Long, body: String) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = Telephony.Sms.STATUS_NONE,
        participants = ArrayList(), date = id.toInt(), read = true, threadId = 1L, isMMS = false, attachment = null,
        senderPhoneNumber = "300012", senderName = "300012", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun vm() = ThreadViewModel(SavedStateHandle()).apply {
        publishItems(listOf(message(1, "Code 111"), message(2, "hello"), message(3, "Your code is ۴۸۲")))
    }

    @Test
    fun matchesAreNewestFirstAndTheButtonsWrap() {
        val vm = vm()
        vm.openSearch()
        vm.updateSearch("CODE")
        assertThat(vm.searchMatches).containsExactly(MessageKey(3, false), MessageKey(1, false)).inOrder()
        assertThat(vm.currentSearchMatch).isEqualTo(MessageKey(3, false))
        vm.showOlderMatch()
        assertThat(vm.currentSearchMatch).isEqualTo(MessageKey(1, false))
        vm.showOlderMatch()
        assertThat(vm.currentSearchMatch).isEqualTo(MessageKey(3, false))
        vm.showNewerMatch()
        assertThat(vm.currentSearchMatch).isEqualTo(MessageKey(1, false))
    }

    @Test
    fun closingClearsTheQueryAndTheHighlight() {
        val vm = vm()
        vm.openSearch()
        vm.updateSearch("482")
        assertThat(vm.activeSearchQuery).isEqualTo("482")
        assertThat(vm.searchMatches).containsExactly(MessageKey(3, false))
        vm.closeSearch()
        assertThat(vm.searchOpen).isFalse()
        assertThat(vm.activeSearchQuery).isEmpty()
        assertThat(vm.searchMatches).isEmpty()
    }
}
