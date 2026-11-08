package com.glooboo.umessage.ui.thread

import android.app.Application
import android.content.ContentValues
import android.provider.Telephony.Sms
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Q12: the delivery line of a sent group message opens each person's state; a chat with one person has none. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h800dp")
class DeliveryDetailsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = app.getString(id)
    private val detailsLabel get() = str(R.string.delivery_details_desc)

    private val ann = GroupPerson("111", "Ann")
    private val bob = GroupPerson("222", "Bob")
    private val cy = GroupPerson("333", "Cy")

    private fun perPerson() = DeliveryDetails(
        listOf(
            RecipientDelivery(ann, RecipientState.Delivered, timeMillis = null),
            RecipientDelivery(bob, RecipientState.NotSent, timeMillis = null),
            RecipientDelivery(cy, RecipientState.Pending, timeMillis = null),
        ),
        perPerson = true,
    )

    // The sheet

    @Test
    fun theSheetTitleCountsThePeopleAndEachOneShowsTheirState() {
        compose.setContent { UMessageTheme { DeliveryDetailsContent(perPerson()) } }

        compose.onNodeWithText(app.getString(R.string.delivery_details_title, 3)).assertIsDisplayed()
        compose.onNodeWithText("Ann").assertExists()
        compose.onNodeWithText("Bob").assertExists()
        compose.onNodeWithText("Cy").assertExists()
        compose.onNodeWithText(str(R.string.thread_status_delivered)).assertExists()
        compose.onNodeWithText(str(R.string.delivery_state_not_sent)).assertExists()
        compose.onNodeWithText(str(R.string.thread_status_sending)).assertExists()
        compose.onNodeWithText(str(R.string.delivery_details_mms_note)).assertDoesNotExist()
    }

    @Test
    fun aFailureReportReadsDeliveryFailed() {
        val details = DeliveryDetails(
            listOf(RecipientDelivery(ann, RecipientState.NotDelivered, timeMillis = null)),
            perPerson = true,
        )
        compose.setContent { UMessageTheme { DeliveryDetailsContent(details) } }

        compose.onNodeWithText(str(R.string.thread_status_delivery_failed)).assertExists()
        compose.onNodeWithText(app.getString(R.string.delivery_details_title, 1)).assertExists()
    }

    @Test
    fun aGroupPictureMessageSaysTheCarrierGivesOneState() {
        val details = DeliveryDetails(
            listOf(ann, bob).map { RecipientDelivery(it, RecipientState.Sent, timeMillis = null) },
            perPerson = false,
        )
        compose.setContent { UMessageTheme { DeliveryDetailsContent(details) } }

        compose.onNodeWithText(str(R.string.delivery_details_mms_note)).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.thread_status_sent)).assertCountEquals(2)
    }

    // The line under the message

    private fun row(delivered: Boolean = false, failed: Boolean = false) =
        ThreadRow.Sent(messageId = 7, delivered = delivered, key = "t7", deliveryFailed = failed)

    @Test
    fun theGroupLineIsAButtonAnnouncedAsDeliveryDetails() {
        var taps = 0
        compose.setContent { UMessageTheme { SentStatusLine(row()) { taps++ } } }

        compose.onNodeWithContentDescription(detailsLabel, useUnmergedTree = true).assertExists()
        compose.onNode(hasClickAction()).assertHasClickAction().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.thread_status_sent)).performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun theTargetIsAtLeast48dpHigh() {
        compose.setContent { UMessageTheme { SentStatusLine(row(delivered = true)) {} } }

        val heightPx = compose.onNode(hasClickAction()).fetchSemanticsNode().size.height
        val density = app.resources.displayMetrics.density
        assertThat(heightPx / density).isAtLeast(48f)
    }

    @Test
    fun aFailedDeliveryOfAGroupMessageOpensTheDetailsToo() {
        var taps = 0
        compose.setContent { UMessageTheme { SentStatusLine(row(failed = true)) { taps++ } } }

        compose.onNodeWithText(str(R.string.thread_status_delivery_failed)).performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun aChatWithOnePersonHasNoDetailsAffordance() {
        compose.setContent { UMessageTheme { SentStatusLine(row(delivered = true), onDetails = null) } }

        compose.onNodeWithText(str(R.string.thread_status_delivered)).assertExists()
        compose.onNodeWithContentDescription(detailsLabel, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun onlyAGroupThreadOffersTheDetailsFromTheRowItself() {
        val group = ThreadViewModel().also { it.isGroup = true }
        compose.setContent { UMessageTheme { ThreadRowItem(row(), group, noActions()) } }

        compose.onNodeWithContentDescription(detailsLabel, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(str(R.string.thread_status_sent)).performClick()
        assertThat(group.deliveryDetailsFor).isEqualTo(MessageKey(7, false))
    }

    @Test
    fun aSingleChatRowItemShowsNoDetailsAffordance() {
        val single = ThreadViewModel()
        compose.setContent { UMessageTheme { ThreadRowItem(row(), single, noActions()) } }

        compose.onNodeWithText(str(R.string.thread_status_sent)).assertExists()
        compose.onNodeWithContentDescription(detailsLabel, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasClickAction()).assertDoesNotExist()
    }

    // The host: the sheet opened from the view model, filled from the provider

    private fun contact(number: String, name: String) = SimpleContact(
        0, 0, name, "", arrayListOf(PhoneNumber(number, 0, "", number)), ArrayList(), ArrayList(),
    )

    @Test
    fun theHostListsEveryPersonWithTheirOwnStateFromTheProvider() {
        FakeSmsMmsProvider.installSms()
        val millis = 1_000_000L
        fun copy(address: String, type: Int, status: Int) = app.contentResolver.insert(
            Sms.CONTENT_URI,
            ContentValues().apply {
                put(Sms.ADDRESS, address)
                put(Sms.BODY, "Dinner at 8?")
                put(Sms.DATE, millis)
                put(Sms.DATE_SENT, 0L)
                put(Sms.TYPE, type)
                put(Sms.STATUS, status)
            },
        )
        copy("111", Sms.MESSAGE_TYPE_SENT, Sms.STATUS_COMPLETE)
        copy("222", Sms.MESSAGE_TYPE_FAILED, Sms.STATUS_NONE)
        val message = Message(
            id = 7, body = "Dinner at 8?", type = Sms.MESSAGE_TYPE_SENT, status = Sms.STATUS_NONE,
            participants = arrayListOf(contact("111", "Ann"), contact("222", "Bob")),
            date = (millis / 1000).toInt(), read = true, threadId = 3, isMMS = false, attachment = null,
            senderPhoneNumber = "111|222", senderName = "", senderPhotoUri = "", subscriptionId = -1,
        )
        val vm = ThreadViewModel().also {
            it.isGroup = true
            it.publishItems(listOf(message))
            it.deliveryDetailsFor = message.key
        }
        compose.setContent {
            UMessageTheme {
                DeliveryDetailsHost(vm.deliveryDetailsFor, vm.items, onClose = { vm.deliveryDetailsFor = null })
            }
        }

        // The overall state shows at once ("Sent" for both); each person's own state replaces it when read.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(str(R.string.delivery_state_not_sent), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.delivery_details_title, 2)).assertExists()
        compose.onNodeWithText("Ann").assertExists()
        compose.onNodeWithText("Bob").assertExists()
        compose.onNodeWithText(str(R.string.thread_status_delivered), substring = true).assertExists()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

private fun noActions() = ThreadActions(
    onBack = {}, onMenuAction = {}, onSelectionAction = {}, onBubbleClick = {}, onSenderClick = {},
    onRetry = { _, _ -> }, onLoadOlder = {}, onSend = {}, onSendLongPress = {}, onAttach = {},
    onEditSchedule = {}, onDiscardSchedule = {}, onSelectSim = {}, onRemoveAttachment = {},
    onOpenFile = { _, _, _ -> }, onOpenVCard = {}, onReplyInfo = {}, onPickContact = {},
    onAddTypedNumber = {}, onRemovePerson = {}, onConfirmPeople = {}, onAddPhoto = {}, onRequestPhotos = {},
)
