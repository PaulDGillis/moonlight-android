package com.limelight.debug

import android.hardware.Sensor
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.InputDevice.MotionRange
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DebugInfoViewModel(
    private val vibrator: Vibrator
): ViewModel() {
    private var lastGamepadRumbleVibrator: Vibrator? = null

    private val testVibrationAmplitudeStateFlow = MutableStateFlow(220)

    private val inputDevicesStateFlow = MutableStateFlow(emptyList<InputDeviceUIState>())

    private val _debugInfoStateFlow = inputDevicesStateFlow.combine(testVibrationAmplitudeStateFlow) { inputDevicesUIState, vibrationAmplitude ->
        DebugPlatformState(
            hasVibrator = vibrator.hasVibrator(),
            testVibrationAmplitude = vibrationAmplitude,
            inputDevices = inputDevicesUIState
        )
    }
    val debugInfoStateFlow: StateFlow<DebugPlatformState> = _debugInfoStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugPlatformState())

    init {
        refreshGamePadList()
    }

    fun changePlatformVibrationAmplitude(amplitude: Int) {
        testVibrationAmplitudeStateFlow.value = amplitude
    }

    private fun InputDevice.getMotionRangeForJoystickAxis(axis: Int): MotionRange? {
        // First get the axis for SOURCE_JOYSTICK then try for SOURCE_GAMEPAD
        return getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) ?: getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
    }

    fun refreshGamePadList() {
        inputDevicesStateFlow.value = InputDevice.getDeviceIds()
            .toTypedArray()
            .mapNotNull { deviceId ->
                InputDevice.getDevice(deviceId)
            }.mapNotNull { device ->
                if (device.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
                    && device.sources and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK)
                    return@mapNotNull null

                if (device.getMotionRangeForJoystickAxis(MotionEvent.AXIS_X) == null
                    || device.getMotionRangeForJoystickAxis(MotionEvent.AXIS_Y) == null)
                    return@mapNotNull null

                // If it makes it this far, it's a gamepad

                val sensorState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasAccelerometer = device.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                    val hasGyro = device.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                    when {
                        hasAccelerometer && hasGyro -> SensorUIState.AccelerometerAndGyroscope
                        hasAccelerometer -> SensorUIState.Accelerometer
                        hasGyro -> SensorUIState.Gyroscope
                        else -> SensorUIState.None
                    }
                } else {
                    SensorUIState.ApiBelowAndroid12
                }

                val hasVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device.vibratorManager.defaultVibrator.hasVibrator()
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrator.hasVibrator()
                }

                InputDeviceUIState(
                    id = device.id,
                    name = device.name,
                    sensorsState = sensorState,
                    vidPid = "${device.vendorId}_${device.productId}\t[${
                        String.format("%04x", device.vendorId)
                    }_${String.format("%04x", device.productId)}]",
                    hasRumble = hasVibrator,
                    details = device.toString()
                )
            }
    }

    fun rumble(deviceId: Int? = null, rumbleType: RumbleType) {
        val vibrator = if (deviceId == null) { vibrator }
        else {
            InputDevice.getDevice(deviceId).let { device ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device?.vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    device?.vibrator
                }
            }?.also {
                lastGamepadRumbleVibrator?.cancel()
                lastGamepadRumbleVibrator = it
            }
        } ?: return // TODO Error?

        val simulatedAmplitude = testVibrationAmplitudeStateFlow.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (rumbleType == RumbleType.OneSecond) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, simulatedAmplitude))
            } else {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(1000), intArrayOf(simulatedAmplitude), 0))
            }
        } else {
            @Suppress("DEPRECATION")
            if (rumbleType == RumbleType.OneSecond) {
                vibrator.vibrate(1000)
            } else {
                val pwmPeriod: Long = 20
                val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
                val offTime = pwmPeriod - onTime
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build()
                vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
            }
        }
    }

    fun cancelRumble() {
        lastGamepadRumbleVibrator?.cancel()
        vibrator.cancel()
    }

    override fun onCleared() {
        cancelRumble()
        super.onCleared()
    }
}