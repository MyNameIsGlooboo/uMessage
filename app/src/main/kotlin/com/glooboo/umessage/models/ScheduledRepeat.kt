package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The repeat rule of a scheduled message. [rule] is one of NONE, DAILY, WEEKLY, MONTHLY, YEARLY and [calendar] one of
 * GREGORIAN, JALALI; [anchorDay] and [anchorMonth] are the day and month (in that calendar) the repeat keeps hitting.
 */
@Entity(tableName = "scheduled_repeats")
data class ScheduledRepeat(
    @ColumnInfo(name = "message_id") @PrimaryKey val messageId: Long,
    @ColumnInfo(name = "rule") val rule: String,
    @ColumnInfo(name = "calendar") val calendar: String,
    @ColumnInfo(name = "anchor_day") val anchorDay: Int,
    @ColumnInfo(name = "anchor_month") val anchorMonth: Int,
)
