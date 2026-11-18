package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.SocHistoryStore
import com.matelink.data.local.entity.SocSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the charge screen's local hourly SOC curve isolated from list state. */
@HiltViewModel
class SocTrendViewModel @Inject constructor(
    private val store: SocHistoryStore
) : ViewModel() {
    private val _samples = MutableStateFlow<List<SocSample>>(emptyList())
    val samples: StateFlow<List<SocSample>> = _samples.asStateFlow()
    private var observation: Job? = null
    private var carId: Int? = null

    fun setCarId(localHistoryCarId: Int?) {
        if (carId == localHistoryCarId) return
        carId = localHistoryCarId
        observation?.cancel()
        observation = null
        _samples.value = emptyList()
        if (localHistoryCarId == null) return
        observation = viewModelScope.launch {
            store.observeRetentionWindow(localHistoryCarId).collect { points ->
                _samples.value = points
            }
        }
    }
}