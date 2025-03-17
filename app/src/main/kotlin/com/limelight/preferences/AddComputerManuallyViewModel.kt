package com.limelight.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AddComputerManuallyUIState(
    val snackbarMessage: String? = null
)

class AddComputerManuallyViewModel : ViewModel() {

    private val snackbarMessageStateFlow = MutableStateFlow(null)

    val state: StateFlow<AddComputerManuallyUIState> = snackbarMessageStateFlow
        .map {
            AddComputerManuallyUIState(it)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddComputerManuallyUIState())
}