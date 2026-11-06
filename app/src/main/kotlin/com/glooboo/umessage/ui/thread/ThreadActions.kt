package com.glooboo.umessage.ui.thread

import android.net.Uri
import com.glooboo.umessage.models.AttachmentSelection
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import org.fossify.commons.models.SimpleContact

enum class ThreadMenuAction {
    Delete, Restore, Archive, Unarchive, Rename, Details, Block, Unblock, ReportSpam, Dial, ManagePeople, MarkUnread,
    AddToContact, CopyNumber, AllowSender, WhyDecided, Search, AlwaysForward, ShareAsText,
    ExportConversation, AddToHomeScreen,
    Labels, Mute, Unmute, Hide, Unhide,
}

enum class SelectionAction {
    Copy, Share, SaveAs, Forward, SelectText, Details, Delete, Restore, ReadAloud, Star, Remind, SaveText, Note,
}

/**
 * The attach sheet's entries (TH-22): Photo picks pictures and videos, Camera asks photo or video ([TakePhoto] and
 * [RecordVideo] are its two answers).
 */
enum class AttachKind {
    Photo, Camera, TakePhoto, RecordVideo, RecordAudio, File, Contact, Location, SavedText, Schedule,
}

/** Everything the thread screen can ask the activity to do. The UI holds no logic beyond layout and gestures. */
data class ThreadActions(
    val onBack: () -> Unit,
    val onMenuAction: (ThreadMenuAction) -> Unit,
    val onSelectionAction: (SelectionAction) -> Unit,
    val onBubbleClick: (Message) -> Unit,
    val onSenderClick: (Message) -> Unit,
    val onRetry: (message: MessageKey, text: String) -> Unit,
    val onLoadOlder: () -> Unit,
    val onSend: () -> Unit,
    val onSendLongPress: () -> Unit,
    val onAttach: (AttachKind) -> Unit,
    val onEditSchedule: () -> Unit,
    val onDiscardSchedule: () -> Unit,
    val onSelectSim: (Int) -> Unit,
    val onRemoveAttachment: (AttachmentSelection) -> Unit,
    val onOpenFile: (uri: Uri, mimeType: String, filename: String) -> Unit,
    val onOpenVCard: (Uri) -> Unit,
    val onReplyInfo: () -> Unit,
    val onPickContact: (SimpleContact) -> Unit,
    val onAddTypedNumber: (String) -> Unit,
    val onRemovePerson: (Int) -> Unit,
    val onConfirmPeople: () -> Unit,
    val onAddPhoto: (Uri) -> Unit,
    val onRequestPhotos: () -> Unit,
)
