package com.limelight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.computers.ComputerManagerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppSelectViewModel : ViewModel() {
    private val computerUuidStateFlow = MutableStateFlow<String?>(null)
    private val serviceBindingStateFlow = MutableStateFlow<ComputerManagerService.ComputerManagerBinder?>(null)

    private val lastRawAppList = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {}

        serviceBindingStateFlow.combine(computerUuidStateFlow) { computerBinder, computerUuid ->
            if (computerBinder != null && computerUuid != null) {
                computerBinder.getComputer(computerUuid)
            } else null // TODO this was originally an exit condition
        }.filterNotNull().map { details ->



        }
    }

    fun onComputerServiceConnected(binder: ComputerManagerService.ComputerManagerBinder) {
        viewModelScope.launch {
            binder.waitForReady()
            serviceBindingStateFlow.update { binder }
        }
    }

    fun onComputerServiceDisconnected() {
        serviceBindingStateFlow.update { null }
    }


}