package com.glooboo.umessage.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Keeps a multi-selection of ids across rotation, folding and process death (`rememberSaveable(saver = ...)`). */
val LongSetStateSaver: Saver<MutableState<Set<Long>>, Any> = listSaver(
    save = { state -> state.value.toList() },
    restore = { saved -> mutableStateOf(saved.toSet()) },
)

/** [LongSetStateSaver] for string keys. */
val StringSetStateSaver: Saver<MutableState<Set<String>>, Any> = listSaver(
    save = { state -> state.value.toList() },
    restore = { saved -> mutableStateOf(saved.toSet()) },
)
