package com.glooboo.umessage.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.dialogs.ScheduleMessageDialog
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.ui.scheduled.ScheduledListScreen
import com.glooboo.umessage.ui.scheduled.ScheduledListViewModel
import com.glooboo.umessage.ui.scheduled.ScheduledRow

/** I25: every scheduled message in one list. */
class ScheduledListActivity : ComposeSimpleActivity() {
    private val viewModel: ScheduledListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUMessageContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            ScheduledListScreen(
                state = state,
                onBack = ::finish,
                onOpen = ::openThread,
                onEditTime = { row ->
                    ScheduleMessageDialog(this, row.millis, row.repeat) { millis, repeat ->
                        if (millis != null) viewModel.reschedule(row, millis, repeat)
                    }
                },
                onSendNow = viewModel::sendNow,
                onDelete = viewModel::delete,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // The list reloads on every change event; this covers messages sent or changed while it was away.
        viewModel.load()
    }

    private fun openThread(row: ScheduledRow) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(THREAD_ID, row.threadId)
                .putExtra(THREAD_TITLE, row.title),
        )
    }
}
