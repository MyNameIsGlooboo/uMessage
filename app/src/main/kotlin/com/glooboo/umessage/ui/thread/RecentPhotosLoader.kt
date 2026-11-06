package com.glooboo.umessage.ui.thread

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/** The newest pictures in the phone's media store, as content uris; reads only what the permission allows. */
class RecentPhotosLoader(private val resolver: ContentResolver) {

    /** Runs on the caller's thread: call it off the main thread. Empty when nothing is allowed or stored. */
    fun load(limit: Int = RECENT_PHOTO_COUNT): List<Uri> = try {
        query(limit)?.use { idsFrom(it, limit) }.orEmpty().map {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun query(limit: Int): Cursor? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "$order LIMIT $limit")
        }
    }

    companion object {
        const val RECENT_PHOTO_COUNT = 12

        /** The first [limit] ids of [cursor], in its order (newest first). */
        fun idsFrom(cursor: Cursor, limit: Int): List<Long> {
            val ids = ArrayList<Long>()
            while (ids.size < limit && cursor.moveToNext()) ids.add(cursor.getLong(0))
            return ids
        }
    }
}
