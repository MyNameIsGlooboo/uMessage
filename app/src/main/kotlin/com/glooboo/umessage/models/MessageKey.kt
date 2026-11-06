package com.glooboo.umessage.models

/**
 * The identity of a message in the app's own database (`messages` and `recycle_bin_messages`, schema 12 and later).
 *
 * The telephony provider keeps SMS and MMS in two tables with independent `_id` sequences, so SMS 57 and MMS 57 are
 * different messages. A provider id alone is therefore never enough to find, update, recycle or delete a row: always
 * use the pair, as the primary key of both tables does (`PRIMARY KEY(id, is_mms)`).
 *
 * - A provider message: `MessageKey(cursor _id, isMMS = it came from Mms.CONTENT_URI)`, or simply [Message.key].
 * - A scheduled message: its id comes from `generateRandomId()` (from schema 12 on always >= SCHEDULED_ID_MIN, far
 *   above any real provider id) and isMMS is whatever it will be sent as. The `*Scheduled*` DAO helpers find it by id
 *   alone, restricted to `is_scheduled = 1`, so they can never reach a provider row.
 *
 * DAO calls take the two parts as separate arguments, e.g. `messagesDB.getMessage(key.id, key.isMMS)`.
 */
data class MessageKey(val id: Long, val isMMS: Boolean)
