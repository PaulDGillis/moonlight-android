package com.limelight.debug

data class DebugPlatformState(
    val hasVibrator: Boolean = false, // Android Vibrator
    val testVibrationAmplitude: Int = 220,
    val inputDevices: List<InputDeviceUIState> = emptyList()
)

data class InputDeviceUIState(
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

