package com.glooboo.umessage.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user label for conversations. [color] is an ARGB int. */
@Entity(tableName = "labels")
data class Label(
    @ColumnInfo(name = "id") @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
