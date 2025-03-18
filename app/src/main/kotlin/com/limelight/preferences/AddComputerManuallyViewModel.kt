package com.limelight.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.R
import com.limelight.StringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AddComputerManuallyUIState(
    val snackbarMessage: StringResource? = null
)

class AddComputerManuallyViewModel : ViewModel() {

    private val snackbarMessageStateFlow = MutableStateFlow<StringResource?>(null)

    val state: StateFlow<AddComputerManuallyUIState> = snackbarMessageStateFlow
        .map {
            AddComputerManuallyUIState(it)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddComputerManuallyUIState())

    fun onIpChanged(ip: String) {
        val hostAddress = ip.trim()

        if (hostAddress.isEmpty()) {
            snackbarMessageStateFlow.value = StringResource.ById(R.string.addpc_enter_ip)
        } else {
            // TODO
        }
    }
}