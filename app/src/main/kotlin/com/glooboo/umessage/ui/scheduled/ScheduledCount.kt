package com.glooboo.umessage.ui.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** How many messages are scheduled, kept up to date while the inbox is on screen: the menu entry shows only above 0. */
@Composable
fun rememberScheduledCount(viewModel: ScheduledListViewModel = viewModel()): Int {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    return if (state.loading) 0 else state.count
}
