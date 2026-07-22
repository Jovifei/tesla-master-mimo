package com.matelink.ui.screens.drives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ParkedDetailUiState(
    val isLoading: Boolean = true,
    val data: ParkedDetailData? = null,
    val error: String? = null
)

@HiltViewModel
class ParkedDetailViewModel @Inject constructor(
    private val repository: TeslamateRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ParkedDetailUiState())
    val uiState: StateFlow<ParkedDetailUiState> = _uiState.asStateFlow()

    fun load(carId: Int, olderDriveId: Int, newerDriveId: Int) {
        viewModelScope.launch {
            _uiState.value = ParkedDetailUiState(isLoading = true)
            _uiState.value = when (val result = repository.getParkedDetail(carId, olderDriveId, newerDriveId)) {
                is ApiResult.Success -> ParkedDetailUiState(isLoading = false, data = result.data)
                is ApiResult.Error -> ParkedDetailUiState(isLoading = false, error = result.message)
            }
        }
    }
}
