package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The pure parts split out of ThreadActivity (TU-50): recipients, rows, menu, SIM choice and MMS rule. */
@RunWith(AndroidJUnit4::class)
class ThreadPureRulesTest {

    private fun message(
        id: Long,
        date: Int,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        read: Boolean = true,
        subscriptionId: Int = -1,
        isMMS: Boolean = false,
        status: Int = Telephony.Sms.STATUS_NONE,
    ) = Message(
        id = id, body = "m$id", type = type, status = status, participants = ArrayList(), date = date, read = read,
        threadId = 1L, isMMS = isMMS, attachment = null, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
        subscriptionId = subscriptionId,
    )

    // region recipients
    @Test
    fun recipientsAreAListEvenForOnePerson() {
        val one = ThreadRecipients.fromNumbers(listOf("111"), name = "Sara")
        assertThat(one.addresses).containsExactly("111")
        assertThat(one.isGroup).isFalse()
        assertThat(one.single?.name).isEqualTo("Sara")
        assertThat(one.reportableAddress).isEqualTo("111")

        val group = ThreadRecipients.fromNumbers(listOf("111", "222", "111"), name = "ignored")
        assertThat(group.isGroup).isTrue()
        // B15: the same phone listed twice is one address, so a send never goes to one person twice.
        assertThat(group.addresses).containsExactly("111", "222").inOrder()
        assertThat(group.distinctAddresses).containsExactly("111", "222").inOrder()
        // A group has no single sender to report, and each number is its own name.
        assertThat(group.reportableAddress).isNull()
        assertThat(group.contacts.map { it.name }).containsExactly("111", "222", "111").inOrder()
    }

    @Test
    fun addingAndRemovingPeopleKeepsTheOriginalUnchanged() {
        val one = ThreadRecipients.fromNumbers(listOf("111"))
        val two = one + ThreadRecipients.contactForNumber("222")
        assertThat(one.size).isEqualTo(1)
        assertThat(two.addresses).containsExactly("111", "222").inOrder()
        assertThat((two + ThreadRecipients.contactForNumber("222")).size).isEqualTo(2)
        assertThat((two - "111".hashCode()).addresses).containsExactly("222")
        assertThat(two.sameAddressesAs(ThreadRecipients.fromNumbers(listOf("111", "222")))).isTrue()
    }

    @Test
    fun theIntentNamesOneNumberOrAGroup() {
        assertThat(ThreadRecipients.numbersFromExtra(null)).isEmpty()
        assertThat(ThreadRecipients.numbersFromExtra("+15551234")).containsExactly("+15551234")
        assertThat(ThreadRecipients.numbersFromExtra("[\"111\",\"222\"]")).containsExactly("111", "222").inOrder()
    }

    @Test
    fun intentNumbersKeepTheirPlusAndNameBareNumbers() {
        val provider = arrayListOf(
            ThreadRecipients.contactForNumber("15551234", name = "15551234"),
            ThreadRecipients.contactForNumber("222", name = "Ali"),
        )
        val fixed = ThreadRecipients.withIntentNumbers(provider, listOf("+15551234"))
        assertThat(fixed[0].phoneNumbers.single().normalizedNumber).isEqualTo("+15551234")
        assertThat(fixed[0].name).isEqualTo("+15551234")
        assertThat(fixed[1].phoneNumbers.single().normalizedNumber).isEqualTo("222")
        assertThat(fixed[1].name).isEqualTo("Ali")
    }

    @Test
    fun copyingSeveralMessagesLabelsEachWithDateAndSender() {
        val received = message(1, date = 10).copy(body = "hi", senderName = "Sara")
        val sent = message(2, date = 20, type = Telephony.Sms.MESSAGE_TYPE_SENT).copy(body = "hello")
        val empty = message(3, date = 30).copy(body = "")
        assertThat(copiedText(listOf(received), { "d" }, "Me")).isEqualTo("hi")
        assertThat(copiedText(listOf(received, sent, empty), { "d${it.date}" }, "Me"))
            .isEqualTo("[d10] Sara: hi\n\n[d20] Me: hello")
    }

    @Test
    fun senderNamesCannotBeRepliedTo() {
        assertThat(ThreadRecipients.fromNumbers(listOf("MyBank")).hasShortCodeWithLetters).isTrue()
        assertThat(ThreadRecipients.fromNumbers(listOf("+15551234")).hasShortCodeWithLetters).isFalse()
    }
    // endregion

    // region rows
    @Test
    fun rowsGetDateHeadersStatusLinesAndUnreadOnlyWhenSeen() {
        val messages = listOf(
            message(3, date = 2_000, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_COMPLETE),
            message(1, date = 1_000, read = false),
            message(2, date = 1_100, type = Telephony.Sms.MESSAGE_TYPE_FAILED, isMMS = true),
        )

        val unseen = buildThreadItems(messages, simLabels = emptyMap(), seen = false)
        assertThat(unseen.newlyRead).isEmpty()
        assertThat(unseen.items).containsExactly(
            ThreadItem.ThreadDateTime(1_000, "?"),
            messages[1],
            messages[2],
            ThreadItem.ThreadError(2, "m2", isMMS = true),
            ThreadItem.ThreadDateTime(2_000, "?"),
            messages[0],
            ThreadItem.ThreadSent(3, delivered = true),
        ).inOrder()

        val seen = buildThreadItems(messages, simLabels = emptyMap(), seen = true)
        assertThat(seen.newlyRead.map { it.id }).containsExactly(1L)
    }

    @Test
    fun aDifferentSimStartsANewHeaderWithItsNumber() {
        val messages = listOf(
            message(1, date = 1_000, subscriptionId = 7),
            message(2, date = 1_010, subscriptionId = 9, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX),
        )
        val items = buildThreadItems(messages, simLabels = mapOf(7 to "1", 9 to "2"), seen = false).items
        assertThat(items).containsExactly(
            ThreadItem.ThreadDateTime(1_000, "1"),
            messages[0],
            ThreadItem.ThreadDateTime(1_010, "2"),
            messages[1],
            ThreadItem.ThreadSending(2),
        ).inOrder()
    }
    // endregion

    // region menu
    private fun menu(
        recipients: ThreadRecipients = ThreadRecipients.fromNumbers(listOf("111")),
        reportedSender: String? = null,
        blockedSender: String? = null,
        isRecycleBin: Boolean = false,
        saved: Boolean = false,
    ) = buildThreadMenu(
        ThreadMenuInput(
            recipients = recipients, hasItems = true, hasConversation = true, isArchived = false,
            isRecycleBin = isRecycleBin, archiveAvailable = true, singleNumberIsSaved = saved,
            reportedSender = reportedSender, blockedSender = blockedSender,
        )
    )

    private fun privacyMenu(muted: Boolean, hiddenChat: Boolean, isRecycleBin: Boolean = false) = buildThreadMenu(
        ThreadMenuInput(
            recipients = ThreadRecipients.fromNumbers(listOf("111")), hasItems = true, hasConversation = true,
            isArchived = false, isRecycleBin = isRecycleBin, archiveAvailable = true, singleNumberIsSaved = false,
            reportedSender = null, blockedSender = null, muted = muted, hiddenChat = hiddenChat,
        )
    )

    @Test
    fun theMenuOffersMuteOrUnmuteAndHideOrUnhide() {
        val plain = privacyMenu(muted = false, hiddenChat = false)
        assertThat(plain.mute).isTrue()
        assertThat(plain.unmute).isFalse()
        assertThat(plain.hide).isTrue()
        assertThat(plain.unhide).isFalse()

        val both = privacyMenu(muted = true, hiddenChat = true)
        assertThat(both.mute).isFalse()
        assertThat(both.unmute).isTrue()
        assertThat(both.hide).isFalse()
        assertThat(both.unhide).isTrue()
    }

    @Test
    fun theRecycleBinOffersNeitherMuteNorHide() {
        val state = privacyMenu(muted = false, hiddenChat = false, isRecycleBin = true)
        assertThat(state.mute).isFalse()
        assertThat(state.hide).isFalse()
    }

    @Test
    fun aSenderNobodyBlockedOffersBlockAndReportButNotUnblock() {
        val state = menu()
        assertThat(state.block).isTrue()
        assertThat(state.unblock).isFalse()
        assertThat(state.reportSpam).isTrue()
        assertThat(state.addToContact).isTrue()
        assertThat(state.dial).isTrue()
        assertThat(state.rename).isFalse()
    }

    @Test
    fun aBlockedSenderOffersUnblockInsteadOfBlockAndReport() {
        val state = menu(reportedSender = "111", blockedSender = "111")
        assertThat(state.block).isFalse()
        assertThat(state.unblock).isTrue()
        assertThat(state.reportSpam).isFalse()
    }

    @Test
    fun aGroupCanBeRenamedAndOpenedButNotBlockedReportedOrDialled() {
        val state = menu(recipients = ThreadRecipients.fromNumbers(listOf("111", "222")), blockedSender = "111")
        // "Block sender" is for one sender; a group has none (recheck 2, N5).
        assertThat(state.block).isFalse()
        assertThat(state.details).isTrue()
        assertThat(state.unblock).isFalse()
        assertThat(state.reportSpam).isFalse()
        assertThat(state.dial).isFalse()
        assertThat(state.rename).isTrue()
        assertThat(state.copyNumber).isFalse()
    }

    @Test
    fun shortCodesAndSenderNamesTakeNoCalls() {
        assertThat(menu(recipients = ThreadRecipients.fromNumbers(listOf("300012"))).dial).isFalse()
        assertThat(menu(recipients = ThreadRecipients.fromNumbers(listOf("VM-HDFCBK"))).dial).isFalse()
        assertThat(menu(recipients = ThreadRecipients.fromNumbers(listOf("07121234567"))).dial).isTrue()
        assertThat(isNumericShortCode("300012")).isTrue()
        assertThat(isNumericShortCode("+44712")).isFalse()
        assertThat(isNumericShortCode("07121234567")).isFalse()
    }

    @Test
    fun aSenderNameIsNotOfferedAsANumber() {
        val name = menu(recipients = ThreadRecipients.fromNumbers(listOf("AX-AMAZN")))
        assertThat(name.copyNumber).isFalse()
        assertThat(name.addToContact).isFalse()
        val shortCode = menu(recipients = ThreadRecipients.fromNumbers(listOf("300012")))
        assertThat(shortCode.copyNumber).isTrue()
        assertThat(shortCode.addToContact).isTrue()
    }

    @Test
    fun aSavedContactIsNotOfferedAlwaysAllow() {
        assertThat(menu(saved = true).allowSender).isFalse()
        assertThat(menu(saved = false).allowSender).isTrue()
    }

    @Test
    fun searchAndAlwaysForwardAreOfferedForOneSender() {
        val state = menu()
        assertThat(state.search).isTrue()
        assertThat(state.alwaysForward).isTrue()
        val group = menu(recipients = ThreadRecipients.fromNumbers(listOf("111", "222")))
        assertThat(group.search).isTrue()
        assertThat(group.alwaysForward).isFalse()
    }

    @Test
    fun theRecycleBinOffersOnlyDeleteAndRestore() {
        val state = menu(isRecycleBin = true)
        assertThat(state).isEqualTo(ThreadMenuState(delete = true, restore = true))
    }
    // endregion

    @Test
    fun simChoiceFollowsUserThenSenderThenDefaults() {
        val sims = listOf(10, 20, 30)
        assertThat(chooseSimIndex(sims, userPreferredSimId = 30, lastReceivedSubscriptionId = 20, appDefaultId = 10, systemDefaultId = 10)).isEqualTo(2)
        assertThat(chooseSimIndex(sims, userPreferredSimId = 0, lastReceivedSubscriptionId = 20, appDefaultId = 10, systemDefaultId = 10)).isEqualTo(1)
        assertThat(chooseSimIndex(sims, userPreferredSimId = 0, lastReceivedSubscriptionId = null, appDefaultId = 30, systemDefaultId = 10)).isEqualTo(2)
        assertThat(chooseSimIndex(sims, userPreferredSimId = 0, lastReceivedSubscriptionId = null, appDefaultId = -1, systemDefaultId = 20)).isEqualTo(1)
        assertThat(chooseSimIndex(sims, userPreferredSimId = 0, lastReceivedSubscriptionId = 99, appDefaultId = -1, systemDefaultId = -1)).isEqualTo(0)
    }

    @Test
    fun groupTextsGoAsMmsOnlyWhenTheUserChoseThat() {
        assertThat(sendsAsMms(hasAttachments = false, recipientCount = 2, sendGroupAsMms = true, isLongText = false)).isTrue()
        assertThat(sendsAsMms(hasAttachments = false, recipientCount = 2, sendGroupAsMms = false, isLongText = false)).isFalse()
        assertThat(sendsAsMms(hasAttachments = false, recipientCount = 1, sendGroupAsMms = true, isLongText = false)).isFalse()
        assertThat(sendsAsMms(hasAttachments = true, recipientCount = 1, sendGroupAsMms = false, isLongText = false)).isTrue()
        assertThat(sendsAsMms(hasAttachments = false, recipientCount = 1, sendGroupAsMms = false, isLongText = true)).isTrue()
    }
}
