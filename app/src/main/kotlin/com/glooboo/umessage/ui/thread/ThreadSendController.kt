package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.messaging.SendOutcome
import com.glooboo.umessage.messaging.sendMessageDetailed
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.MessageKey

/**
 * One message to send now. It goes to every address in [addresses]: one for a chat with one person, several for a
 * group (as one group MMS or as separate texts, as the settings say). Nothing here assumes a single number.
 */
data class OutgoingMessage(
    val text: String,
    val addresses: List<String>,
    val subscriptionId: Int,
    val attachments: List<Attachment> = emptyList(),
    /** The failed message this replaces ("Retry"), matched by id and type. */
    val resendOf: MessageKey? = null,
    /** Told about each attachment queued, so a scheduled message can drop it (MS6). */
    val onAttachmentHandedOff: (Attachment) -> Unit = {},
)

/**
 * Hands a message to the system. [SendOutcome.handedOff] is false when something did not reach the provider (for a
 * message with several attachments, [SendOutcome] says which parts did); may throw.
 */
fun interface MessageSender {
    fun send(message: OutgoingMessage): SendOutcome
}

/** [MessageSender] through the app's SMS/MMS code. */
class ContextMessageSender(private val context: Context) : MessageSender {
    override fun send(message: OutgoingMessage): SendOutcome = context.sendMessageDetailed(
        text = message.text,
        addresses = message.addresses,
        subId = message.subscriptionId,
        attachments = message.attachments,
        resend = message.resendOf,
        onAttachmentHandedOff = message.onAttachmentHandedOff,
    )
}

/**
 * The send path of the thread screen. [send] hands the message off and reports errors through [onError]; it says
 * whether the composer may be emptied: only for a message typed there that was really handed off, so after a
 * failure the user's text and photos stay for another try.
 */
class ThreadSendController(
    private val sender: MessageSender,
    private val onError: (Throwable) -> Unit,
) {
    /**
     * [completed] is false when the send threw: nothing new can be in the provider then. A send that returned,
     * even unsuccessfully, may have left a failed row (with Retry) to show.
     */
    data class Result(
        val handedOff: Boolean,
        val clearComposer: Boolean,
        val completed: Boolean,
        /** Set when only some attachments went out: what is left to send, and whether the text already went. */
        val partial: SendOutcome? = null,
    )

    fun send(message: OutgoingMessage, fromComposer: Boolean): Result {
        val outcome = try {
            sender.send(message)
        } catch (e: Exception) {
            onError(e)
            return Result(handedOff = false, clearComposer = false, completed = false)
        } catch (e: Error) {
            onError(e)
            return Result(handedOff = false, clearComposer = false, completed = false)
        }
        val handedOff = outcome.handedOff
        return Result(
            handedOff,
            shouldClearComposerAfterSend(handedOff, fromComposer),
            completed = true,
            partial = outcome.takeIf { it.partlySent },
        )
    }
}

/** Lets one send from the composer run at a time: the second tap on Send while it runs is ignored (MS5). */
class SendGuard {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True when the caller may start; then it must call [finish]. */
    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() = running.set(false)
}

/**
 * Which failed message the composer is about to resend. "Retry" puts the failed text into the composer; that text
 * change must not forget the target, while any edit by the user (or a new attachment) does.
 */
class ResendTracker {
    var target: MessageKey? = null
        private set
    private var expectedText: String? = null

    /** Called by "Retry", right before it puts [text] into the composer. */
    fun prepare(target: MessageKey, text: String) {
        expectedText = text
        this.target = target
    }

    /** Every composer text change. */
    fun onTextChanged(text: String) {
        val programmatic = expectedText != null && expectedText == text
        expectedText = null
        if (!programmatic) target = null
    }

    /** The composer changed in another way (an attachment was added) or the conversation changed. */
    fun clear() {
        target = null
        expectedText = null
    }
}
