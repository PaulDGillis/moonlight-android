package com.limelight.debug

data class DebugScreenUIState(
    val hasOsVibrator: Boolean = false, // Android Vibrator
    val settingsState: DebugSettingsUIState = DebugSettingsUIState(),
    val inputDevicesState: DebugInputDevicesUIState = DebugInputDevicesUIState.Loading
)

data class DebugSettingsUIState(
    val isSettingsShowing: Boolean = false,
    val isContinuousVibration: Boolean = false,
    val amplitudeForTestVibration: Int = 220,
)

sealed class DebugInputDevicesUIState {
    data object Loading: DebugInputDevicesUIState()
    data class Success(
        val inputDevices: List<DebugInputDeviceUIState> = emptyList()
    ): DebugInputDevicesUIState()
}

data class DebugInputDeviceUIState(
    val id: Int = -1,
    val name: String = "",
    val sensorsState: SensorUIState = SensorUIState.None,
    val vidPid: String = "",
    val hasRumble: Boolean = false,
    val details: String = ""
)

enum class SensorUIState {
    Accelerometer,
    Gyroscope,
    AccelerometerAndGyroscope,
    None,
    ApiBelowAndroid12
}

