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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DebugInfoViewModel(
    private val vibrator: Vibrator
): ViewModel() {
    private var lastGamepadRumbleVibrator: Vibrator? = null

    private val showSettingsSheetStateFlow = MutableStateFlow(false)
    private val testVibrationAmplitudeStateFlow = MutableStateFlow(220)
    private val testVibrationIsContinuousStateFlow = MutableStateFlow(false)

    private val inputDevicesStateFlow = MutableStateFlow<DebugInputDevicesUIState>(DebugInputDevicesUIState.Loading)

    val debugInfoStateFlow = combine(
        showSettingsSheetStateFlow,
        testVibrationAmplitudeStateFlow,
        testVibrationIsContinuousStateFlow,
        inputDevicesStateFlow
    ) { showSettingsSheet, vibrationAmplitude, vibrationIsContinous, inputDevicesUIState ->
        DebugScreenUIState(
            hasOsVibrator = vibrator.hasVibrator(),
            settingsState = DebugSettingsUIState(
                isSettingsShowing = showSettingsSheet,
                amplitudeForTestVibration = vibrationAmplitude,
                isContinuousVibration = vibrationIsContinous,
            ),
            inputDevicesState = inputDevicesUIState
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugScreenUIState())

    init {
        refreshGamePadList()
    }

    fun toggleSettingsSheet() {
        showSettingsSheetStateFlow.value = showSettingsSheetStateFlow.value.not()
    }

    fun changePlatformVibrationAmplitude(amplitude: Int) {
        testVibrationAmplitudeStateFlow.value = amplitude
    }

    fun changeVibrationType(isContinuous: Boolean) {
        testVibrationIsContinuousStateFlow.value = isContinuous
    }

    private fun InputDevice.getMotionRangeForJoystickAxis(axis: Int): MotionRange? {
        // First get the axis for SOURCE_JOYSTICK then try for SOURCE_GAMEPAD
        return getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) ?: getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
    }

    fun refreshGamePadList() {
        inputDevicesStateFlow.value = DebugInputDevicesUIState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            delay(50)
            val devices = InputDevice.getDeviceIds()
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

                    DebugInputDeviceUIState(
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
            inputDevicesStateFlow.value = DebugInputDevicesUIState.Success(devices)
        }
    }

    fun rumble(deviceId: Int? = null) {
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
        val isContinuous = testVibrationIsContinuousStateFlow.value

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isContinuous) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(1000), intArrayOf(simulatedAmplitude), 0))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, simulatedAmplitude))
            }
        } else {
            @Suppress("DEPRECATION")
            if (isContinuous) {
                val pwmPeriod: Long = 20
                val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
                val offTime = pwmPeriod - onTime
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build()
                vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
            } else {
                vibrator.vibrate(1000)
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