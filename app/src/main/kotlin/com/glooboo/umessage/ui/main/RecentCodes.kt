package com.glooboo.umessage.ui.main

import android.content.Context
import android.provider.Telephony.Sms
import com.glooboo.umessage.helpers.OtpCodeExtractor

/** ID-23: a one-time code offered as a tap-to-copy chip on the empty search page. */
data class RecentCode(val sender: String, val code: String)

/** A received SMS, only the fields the recent-codes list needs. */
data class ReceivedSms(val threadId: Long, val address: String, val body: String, val date: Long)

/** A code found in a received SMS; the sender's display name is filled in later from the conversation list. */
data class FoundCode(val threadId: Long, val address: String, val code: String, val date: Long)

/**
 * B9, ID-23: "Recent codes" lists the codes received in the last 24 hours, read or unread, newest first, one chip per
 * code. The row chip in the list ([com.glooboo.umessage.ui.conversations.ConversationRow.otpCode]) stays short-lived;
 * this list is wider on purpose, since a code is often needed again after the message was opened.
 */
object RecentCodes {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    const val MAX_CODES = 5

    /** Messages read at most, newest first, so a busy day never scans the whole inbox. */
    private const val MAX_SCANNED = 60

    /** Codes in [messages] received within [WINDOW_MS] before [now], newest first, each code once. */
    fun pick(
        messages: List<ReceivedSms>,
        now: Long,
        extract: (String) -> String? = OtpCodeExtractor::extract,
    ): List<FoundCode> = messages
        .asSequence()
        .filter { now - it.date in 0..WINDOW_MS }
        .sortedByDescending { it.date }
        .mapNotNull { sms -> extract(sms.body)?.let { FoundCode(sms.threadId, sms.address, it, sms.date) } }
        .distinctBy { it.code }
        .take(MAX_CODES)
        .toList()

    /**
     * Chips for [found], named by the conversation title in [titles] (by thread id) or else the sender's address.
     * [fallback] (codes the list rows already show) fills in when the inbox could not be read.
     */
    fun chips(found: List<FoundCode>, titles: Map<Long, String>, fallback: List<RecentCode>): List<RecentCode> {
        val named = found.map { RecentCode(titles[it.threadId]?.takeIf(String::isNotBlank) ?: it.address, it.code) }
        return (named + fallback).distinctBy { it.code }.take(MAX_CODES)
    }

    /** Received SMS of the last [WINDOW_MS], newest first; empty when the inbox cannot be read. */
    fun read(context: Context, now: Long): List<ReceivedSms> = runCatching {
        val projection = arrayOf(Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE)
        val messages = ArrayList<ReceivedSms>()
        context.contentResolver.query(
            Sms.Inbox.CONTENT_URI,
            projection,
            "${Sms.DATE} > ?",
            arrayOf((now - WINDOW_MS).toString()),
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            val thread = cursor.getColumnIndexOrThrow(Sms.THREAD_ID)
            val address = cursor.getColumnIndexOrThrow(Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Sms.DATE)
            while (cursor.moveToNext() && messages.size < MAX_SCANNED) {
                messages += ReceivedSms(
                    threadId = cursor.getLong(thread),
                    address = cursor.getString(address).orEmpty(),
                    body = cursor.getString(body).orEmpty(),
                    date = cursor.getLong(date),
                )
            }
        }
        messages
    }.getOrDefault(emptyList())
}
