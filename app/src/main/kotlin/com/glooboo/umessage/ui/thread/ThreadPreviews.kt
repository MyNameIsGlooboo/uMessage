package com.glooboo.umessage.ui.thread

import android.content.res.Configuration
import android.provider.Telephony
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.ui.theme.UMessageTheme

private const val PREVIEW_DATE = 1_761_800_000

private fun previewMessage(id: Long, body: String, received: Boolean, minutes: Int) = Message(
    id = id,
    body = body,
    type = if (received) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
    status = Telephony.Sms.STATUS_NONE,
    participants = ArrayList(),
    date = PREVIEW_DATE + minutes * 60,
    read = true,
    threadId = 1,
    isMMS = false,
    attachment = null,
    senderPhoneNumber = if (received) "+15551230001" else "",
    senderName = if (received) "Maya Rivera" else "",
    senderPhotoUri = "",
    subscriptionId = 1,
)

private val previewItems = listOf(
    ThreadItem.ThreadDateTime(PREVIEW_DATE, ""),
    previewMessage(1, "Are we still on for tonight?", received = true, minutes = 0),
    previewMessage(2, "Bring the keys, I forgot mine at the office", received = true, minutes = 1),
    previewMessage(3, "Yes! See you at 6", received = false, minutes = 3),
    ThreadItem.ThreadSent(3, delivered = true),
)

private val noActions = ThreadActions(
    onBack = {}, onMenuAction = {}, onSelectionAction = {}, onBubbleClick = {}, onSenderClick = {},
    onRetry = { _, _ -> }, onLoadOlder = {}, onSend = {}, onSendLongPress = {}, onAttach = {},
    onEditSchedule = {}, onDiscardSchedule = {}, onSelectSim = {}, onRemoveAttachment = {},
    onOpenFile = { _, _, _ -> }, onOpenVCard = {}, onReplyInfo = {}, onPickContact = {},
    onAddTypedNumber = {}, onRemovePerson = {}, onConfirmPeople = {},
    onAddPhoto = {}, onRequestPhotos = {},
)

@Composable
private fun PreviewHost(draft: String = "", setup: ThreadViewModel.() -> Unit = {}) = UMessageTheme {
    val vm = remember {
        ThreadViewModel().apply {
            title = "Maya Rivera"
            subtitle = "+1 555-123-0001"
            loaded = true
            menu = ThreadMenuState(details = true, dial = true, reportSpam = true, block = true)
            publishItems(previewItems)
            text.edit { append(draft) }
            setup()
        }
    }
    ThreadScreen(vm, noActions)
}

@Preview(name = "Thread - empty field", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ThreadLightPreview() = PreviewHost()

@Preview(
    name = "Thread - empty field dark", showBackground = true, widthDp = 360, heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThreadDarkPreview() = PreviewHost()

@Preview(name = "Thread - typing, near the SMS limit", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ThreadTypingPreview() = PreviewHost(draft = "On my way, running about ten minutes late") {
    segments = SmsSegments(parts = 1, remaining = 12, unicode = false)
}

@Preview(name = "Thread - Persian text over one SMS", showBackground = true, widthDp = 360, heightDp = 640, locale = "fa")
@Composable
private fun ThreadPersianPartsPreview() = PreviewHost(draft = "س".repeat(80)) {
    segments = SmsSegments.of("س".repeat(80))
}

@Preview(
    name = "Thread - MMS dark", showBackground = true, widthDp = 360, heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThreadMmsDarkPreview() = PreviewHost(draft = "Photos from the lake") { isMms = true }

@Preview(name = "Thread - RTL", showBackground = true, widthDp = 360, heightDp = 640, locale = "fa")
@Composable
private fun ThreadRtlPreview() = PreviewHost(draft = "سلام، تا ده دقیقه دیگه می‌رسم")

@Preview(name = "Thread - large text", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.5f)
@Composable
private fun ThreadLargeTextPreview() = PreviewHost(draft = "See you soon") { isMms = true }
