package com.glooboo.umessage.ui.thread

/**
 * Owner notes r2: as in Telegram, once a message is selected its text can be selected in place, by a long press
 * and the drag handles, to copy just a part of it.
 *
 * Only while exactly this one message is selected: then the bubble gives up its own long press (which would only
 * toggle the selection again) to the text, so the two long presses never compete. With two or more messages
 * selected the bubbles keep their normal long press and tap, and partial selection makes no sense anyway.
 */
internal fun canSelectTextInPlace(selectedIds: Set<Long>, messageId: Long, body: String): Boolean =
    selectedIds.size == 1 && messageId in selectedIds && body.isNotBlank()
