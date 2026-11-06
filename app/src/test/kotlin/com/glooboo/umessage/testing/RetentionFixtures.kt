package com.glooboo.umessage.testing

import com.glooboo.umessage.models.Message

/** Invented messages for the automatic cleanup tests: received by default, dated [date] seconds. */
fun retentionMessage(
    id: Long,
    body: String = "Hello, see you later",
    date: Int = 1_000,
    read: Boolean = true,
    type: Int = 1,
    threadId: Long = 7,
    mms: Boolean = false,
    scheduled: Boolean = false,
) = Message(
    id = id,
    body = body,
    type = type,
    status = -1,
    participants = ArrayList(),
    date = date,
    read = read,
    threadId = threadId,
    isMMS = mms,
    attachment = null,
    senderPhoneNumber = "BankName",
    senderName = "",
    senderPhotoUri = "",
    subscriptionId = 0,
    isScheduled = scheduled,
)

const val RETENTION_CODE_BODY = "Your verification code is 482913. Do not share it."
