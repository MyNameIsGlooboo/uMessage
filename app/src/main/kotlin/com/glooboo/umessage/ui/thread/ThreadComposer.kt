package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.localizedNumber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.extensions.isPlayableAudioMimeType
import com.glooboo.umessage.extensions.isVCardMimeType
import com.glooboo.umessage.extensions.isVideoMimeType

private val NoGestures = AttachmentGestures(selecting = false, onToggleSelect = {}, onLongClick = {})
private const val MAX_INPUT_LINES = 6
private const val EMOJI_PANEL_HEIGHT_FRACTION = 0.4f
private val EmojiPanelMaxHeight = 320.dp

/**
 * Bottom bar: scheduled-send banner, picked attachments, the text field with emoji and gallery shortcuts, and the
 * send button, which shows only once there is something to send (as in Google Messages). The emoji panel opens
 * in the keyboard's place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThreadComposer(vm: ThreadViewModel, actions: ThreadActions) {
    val focus = LocalFocusManager.current
    val emojiPanel = vm.showEmojiPanel && !vm.replyDisabled
    // The keyboard coming up (the field was tapped) closes the emoji panel; opening the panel hides the keyboard.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) vm.showEmojiPanel = false }
    BackHandler(enabled = emojiPanel) { vm.showEmojiPanel = false }
    // While the panel is open it stands in for the keyboard, so the keyboard's inset is not added on top of it.
    val insets = WindowInsets.safeDrawing.let { if (emojiPanel) it.exclude(WindowInsets.ime) else it }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        if (vm.replyDisabled) {
            ReplyDisabledBar(onInfo = actions.onReplyInfo)
        } else {
            vm.scheduledLabel?.let { ScheduledBanner(it, actions.onEditSchedule, actions.onDiscardSchedule) }
            vm.groupSendMode?.let { GroupModeNote(it) }
            SmartReplyChips(vm)
            if (vm.attachments.isNotEmpty()) AttachmentPreviews(vm, actions)
            InputRow(vm, actions, focus)
            if (emojiPanel) EmojiPanel(onEmoji = { vm.insertAtCursor(it) })
        }
    }
    if (vm.showAttachSheet) {
        AttachSheet(
            photoAccessVersion = vm.photoAccessVersion,
            onDismiss = { vm.showAttachSheet = false },
            onPick = {
                vm.showAttachSheet = false
                actions.onAttach(it)
            },
            onPhoto = {
                vm.showAttachSheet = false
                actions.onAddPhoto(it)
            },
            onAllowPhotos = actions.onRequestPhotos,
        )
    }
    if (vm.showSavedTexts) {
        SavedTextSheet(
            onDismiss = { vm.showSavedTexts = false },
            onInsert = {
                vm.showSavedTexts = false
                vm.insertAtCursor(it)
            },
        )
    }
}

@Composable
private fun ReplyDisabledBar(onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // TH-30: only sender names (letters in the address) land here, never numeric short codes.
            stringResource(R.string.thread_review_reply_disabled),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onInfo) {
            Icon(Symbols.Info, contentDescription = stringResource(R.string.thread_more_info))
        }
    }
}

/** MC-13: says how a group message goes out, as the "Send group messages as MMS" setting decides. */
@Composable
private fun GroupModeNote(asMms: Boolean) {
    Text(
        stringResource(if (asMms) R.string.thread_group_note_mms else R.string.thread_group_note_separate),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
}

@Composable
private fun ScheduledBanner(label: String, onEdit: () -> Unit, onDiscard: () -> Unit) {
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Symbols.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.thread_scheduled_for, label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDiscard) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.cancel_schedule_send), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AttachmentPreviews(vm: ThreadViewModel, actions: ThreadActions) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vm.attachments, key = { it.id }) { attachment ->
            val mimeType = attachment.mimetype
            val remove = { actions.onRemoveAttachment(attachment) }
            val open = { actions.onOpenFile(attachment.uri, mimeType, attachment.filename) }
            when {
                mimeType.isImageMimeType() || mimeType.isVideoMimeType() ->
                    ComposerMediaThumbnail(attachment, mimeType.isVideoMimeType(), onOpen = open, onRemove = remove)

                mimeType.isPlayableAudioMimeType() ->
                    AudioAttachment(attachment.uri, NoGestures, onOpenWith = open, onRemove = remove)

                mimeType.isVCardMimeType() ->
                    VCardAttachment(
                        attachment.uri,
                        NoGestures,
                        onOpen = { actions.onOpenVCard(attachment.uri) },
                        modifier = Modifier.widthIn(max = 260.dp),
                        onRemove = remove,
                    )

                else -> DocumentAttachment(
                    attachment.uri,
                    attachment.filename,
                    mimeType,
                    NoGestures,
                    onOpen = open,
                    modifier = Modifier.widthIn(max = 260.dp),
                    onRemove = remove,
                )
            }
        }
    }
}

@Suppress("ViewModelForwarding")
@Composable
private fun InputRow(vm: ThreadViewModel, actions: ThreadActions, focus: FocusManager) {
    val colors = MaterialTheme.colorScheme
    val scheduling = vm.scheduledLabel != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = {
                focus.clearFocus()
                vm.showEmojiPanel = false
                vm.showAttachSheet = true
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Symbols.Add, contentDescription = stringResource(R.string.thread_attach))
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceVariant,
            contentColor = colors.onSurface,
            modifier = Modifier.weight(1f),
        ) {
            // Right-to-left text mirrors the whole field: it starts at the right edge, the emoji button moves left.
            val fieldDirection = if (startsRightToLeft(vm.text.text)) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides fieldDirection) {
                // The small counter is stacked above the emoji button, so the text column keeps its width.
                Row(verticalAlignment = Alignment.Bottom) {
                    MessageField(vm, actions, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FieldCounter(vm)
                        EmojiToggle(vm)
                    }
                }
            }
        }
        SendSlot(vm, actions, scheduling)
    }
}

@Composable
private fun SendSlot(vm: ThreadViewModel, actions: ThreadActions, scheduling: Boolean) {
    val startVoiceTyping = rememberVoiceTyping(vm)
    if (startVoiceTyping != null) {
        // I40: the microphone takes Send's place while the field is empty, so the row never changes width.
        Crossfade(targetState = showsVoiceButton(vm.voiceButton, vm.canSend), label = "send-or-voice") { voice ->
            Box(Modifier.size(48.dp)) {
                if (voice) {
                    VoiceButton(onClick = startVoiceTyping)
                } else {
                    SendButton(vm, actions, scheduling)
                }
            }
        }
    } else {
        // Send appears with the first character or attachment, so an empty field never offers a dead button.
        AnimatedVisibility(
            visible = vm.canSend,
            enter = fadeIn() + scaleIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + scaleOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            SendButton(vm, actions, scheduling)
        }
    }
}

/**
 * The action that starts voice typing, or null when the setting is off or the phone has no speech recognizer. The
 * recognised text goes in at the cursor; nothing is sent.
 */
@Composable
private fun rememberVoiceTyping(vm: ThreadViewModel): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && spoken != null) {
            val before = vm.text.text.subSequence(0, vm.text.selection.min)
            vm.insertAtCursor(VoiceTyping.textToInsert(before, spoken))
            vm.focusInput++
        }
    }
    val available = remember { VoiceTyping.isAvailable(context) }
    if (!vm.voiceButton || !available) return null
    return {
        val tag = VoiceTyping.languageTag(
            AppCompatDelegate.getApplicationLocales().toLanguageTags(),
            Locale.getDefault().toLanguageTag(),
        )
        try {
            launcher.launch(VoiceTyping.recognizeIntent(tag))
        } catch (_: ActivityNotFoundException) {
            // The recognizer went away since the check; the microphone does nothing until the next recomposition.
        }
    }
}

/** I40: the microphone stands where Send is while the field is empty and nothing is attached, if the setting is on. */
internal fun showsVoiceButton(enabled: Boolean, canSend: Boolean): Boolean = enabled && !canSend

@Composable
private fun VoiceButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Symbols.Mic, contentDescription = stringResource(R.string.i40_record_voice))
        }
    }
}

@Composable
private fun MessageField(vm: ThreadViewModel, actions: ThreadActions, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge.contentDirection().copy(color = MaterialTheme.colorScheme.onSurface)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(vm.focusInput) { if (vm.focusInput > 0) focusRequester.requestFocus() }
    BasicTextField(
        state = vm.text,
        modifier = modifier
            .focusRequester(focusRequester)
            .heightIn(min = 48.dp)
            .onPreviewKeyEvent { event ->
                if (vm.sendOnEnter && event.key == Key.Enter && !event.isShiftPressed) {
                    if (event.type == KeyEventType.KeyUp && vm.canSend) actions.onSend()
                    true
                } else {
                    false
                }
            },
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = MAX_INPUT_LINES),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (vm.sendOnEnter) ImeAction.Send else ImeAction.Default,
        ),
        onKeyboardAction = KeyboardActionHandler { if (vm.sendOnEnter && vm.canSend) actions.onSend() },
        decorator = TextFieldDecorator { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (vm.text.text.isEmpty()) {
                    Text(
                        stringResource(R.string.type_a_message),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The text field takes the whole width. Left to its own size it was only as wide as the text, and
                // trailing spaces add no width, so typing only spaces left the cursor at the start (TU-50 leftover).
                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                    inner()
                }
            }
        },
    )
}

/**
 * "12" left in a one-part SMS, "12 · 3" (characters left, parts) once the text needs more than one part (RM-46). The
 * text is a bidi isolate (U+2066 ... U+2069), so the order and the dot read the same in a left-to-right and in a
 * right-to-left screen.
 */
@Composable
internal fun segmentLabel(segments: SmsSegments): String {
    val text = if (segments.isMultipart) {
        stringResource(R.string.thread_counter_parts, segments.remaining, segments.parts)
    } else {
        localizedNumber(segments.remaining, uiLocale())
    }
    return "\u2066$text\u2069"
}

/** The counter read out in full; Unicode text also says how much fits in one SMS. */
@Composable
private fun segmentDescription(segments: SmsSegments): String {
    val parts = pluralStringResource(R.plurals.thread_counter_description, segments.parts, segments.parts, segments.remaining)
    return if (segments.unicode) {
        stringResource(R.string.thread_counter_unicode_description, parts, segments.perPart)
    } else {
        parts
    }
}

/**
 * The small, dim counter on the emoji button's row: "MMS" when the message costs more, otherwise the characters left
 * and parts. Shown only when needed (see [shouldShowCounter]); it sits centred above the emoji button, as wide as
 * the button, so the text keeps its width and the box grows by one counter line only while it shows.
 */
@Composable
private fun FieldCounter(vm: ThreadViewModel) {
    val segments = vm.segments
    if (!shouldShowCounter(vm.text.text.length, segments, vm.isMms, vm.showCounter)) return
    val mmsLabel = stringResource(R.string.mms)
    val label: String
    val description: String
    if (vm.isMms || segments == null) {
        label = mmsLabel
        description = mmsLabel
    } else {
        label = segmentLabel(segments)
        description = segmentDescription(segments)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(end = 4.dp)
            .width(48.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}

/** The emoji shortcut at the field's end. */
@Composable
private fun EmojiToggle(vm: ThreadViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    // A 48dp button already leaves 12dp around its 24dp glyph, so 4dp more puts the emoji glyph 16dp from the field's
    // end, the same inset the text has at the start.
    IconButton(
        onClick = {
            if (vm.showEmojiPanel) {
                vm.showEmojiPanel = false
                vm.focusInput++
                keyboard?.show()
            } else {
                keyboard?.hide()
                vm.showEmojiPanel = true
            }
        },
        modifier = Modifier
            .padding(end = 4.dp)
            .size(48.dp),
    ) {
        if (vm.showEmojiPanel) {
            Icon(ThreadIcons.Keyboard, contentDescription = stringResource(R.string.thread_show_keyboard))
        } else {
            Icon(ThreadIcons.Mood, contentDescription = stringResource(R.string.thread_emoji))
        }
    }
}

/**
 * The AndroidX emoji picker (categories, recents, skin tones; it leaves out emoji the system font cannot draw),
 * shown where the keyboard was. Picked emoji go in at the cursor.
 */
@Composable
private fun EmojiPanel(onEmoji: (String) -> Unit) {
    val onPicked by rememberUpdatedState(onEmoji)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { (windowHeight * EMOJI_PANEL_HEIGHT_FRACTION).toDp() }.coerceAtMost(EmojiPanelMaxHeight)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply { setOnEmojiPickedListener { onPicked(it.emoji) } }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Send button. With two SIMs it carries the number of the SIM the message goes out on as a small badge;
 * long-press opens the SIM picker (scheduling lives in the attach sheet). With one SIM, long-press schedules.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(vm: ThreadViewModel, actions: ThreadActions, scheduling: Boolean) {
    val colors = MaterialTheme.colorScheme
    var simMenu by remember { mutableStateOf(false) }
    val dualSim = vm.sims.size > 1
    val simId = vm.sims.getOrNull(vm.simIndex)?.id
    val sendLabel = stringResource(if (scheduling) R.string.thread_send_scheduled else R.string.thread_send)
    val description = if (dualSim && simId != null) {
        stringResource(R.string.ui_send_with_sim, sendLabel, stringResource(R.string.thread_sim, simId))
    } else {
        sendLabel
    }
    Box {
        Surface(
            shape = CircleShape,
            color = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = vm.canSend,
                    role = Role.Button,
                    onLongClickLabel = if (dualSim) stringResource(R.string.thread_change_sim) else null,
                    onClick = { if (vm.canSend) actions.onSend() },
                    onLongClick = {
                        if (dualSim) simMenu = true else if (!scheduling) actions.onSendLongPress()
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (scheduling) Symbols.ScheduleSendFilled else Symbols.SendFilled,
                    contentDescription = description,
                )
            }
        }
        if (dualSim && simId != null) {
            // A fixed square with a circle shape: sized by the font scale only, never by the digit or by the send
            // button's enter animation (which squeezed a content-sized badge into a pill before it settled).
            val badgeSize = 18.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
            Surface(
                shape = CircleShape,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(badgeSize)
                    .clearAndSetSemantics { },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        localizedNumber(simId, uiLocale()),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
            vm.sims.forEachIndexed { index, sim ->
                DropdownMenuItem(
                    text = { Text(sim.label, maxLines = 2) },
                    leadingIcon = { Icon(Symbols.SimCard, contentDescription = null) },
                    trailingIcon = if (index == vm.simIndex) {
                        { Icon(Symbols.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        simMenu = false
                        actions.onSelectSim(index)
                    },
                )
            }
        }
    }
}
