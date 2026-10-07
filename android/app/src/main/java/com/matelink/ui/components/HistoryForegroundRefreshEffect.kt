package com.matelink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matelink.domain.history.HistoryResumeGate

@Composable
internal fun HistoryForegroundRefreshEffect(onRefresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onRefresh)
    val gate = remember(owner) { HistoryResumeGate() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> gate.onStop()
                Lifecycle.Event.ON_RESUME -> if (gate.onResume()) callback.value()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
