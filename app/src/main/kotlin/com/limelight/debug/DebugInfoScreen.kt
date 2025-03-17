package com.limelight.debug

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.R
import com.limelight.utils.DeviceUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

sealed class UIAction {
    data object RefreshGamepadList : UIAction()
    data object ToggleShowSettings: UIAction()
    data class ChangeVibrationAmplitude(val amplitude: Int) : UIAction()
    data class ChangeVibrationType(val isContinuous: Boolean): UIAction()
    data class TestGamepadRumble(val deviceId: Int) : UIAction()
    data object TestOSRumble : UIAction()
    data object CancelRumble : UIAction()
}

@ExperimentalMaterial3Api
@Composable
fun DebugInfoScreen(
    viewModel: DebugInfoViewModel = koinViewModel()
) {
    val state by viewModel.debugInfoStateFlow.collectAsStateWithLifecycle()
    DebugInfoScreen(
        state = state,
        onUIAction = { action ->
            when (action) {
                UIAction.RefreshGamepadList -> viewModel.refreshGamePadList()
                UIAction.TestOSRumble -> viewModel.rumble()
                UIAction.ToggleShowSettings -> viewModel.toggleSettingsSheet()
                is UIAction.TestGamepadRumble -> viewModel.rumble(action.deviceId)
                is UIAction.ChangeVibrationAmplitude -> viewModel.changePlatformVibrationAmplitude(action.amplitude)
                is UIAction.ChangeVibrationType -> viewModel.changeVibrationType(action.isContinuous)
                UIAction.CancelRumble -> viewModel.cancelRumble()
            }
        }
    )
}

@ExperimentalMaterial3Api
@Composable
private fun DebugInfoScreen(
    state: DebugScreenUIState,
    onUIAction: (UIAction) -> Unit = {},
) {
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = @Composable { Text(stringResource(R.string.debug_info_stop_vibration)) },
                icon = @Composable { Icon(Icons.Filled.Clear, "Cancel Vibration") },
                onClick = { onUIAction(UIAction.CancelRumble) }
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_info)) },
                actions = {
                    IconButton(onClick = { onUIAction(UIAction.ToggleShowSettings) }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(Modifier.padding(contentPadding)) {
            DebugInfoContent(state, onUIAction)
            DebugSettingsBottomSheet(state.settingsState, onUIAction)
        }
    }
}

@ExperimentalMaterial3Api
@Composable
private fun DebugInfoContent(
    state: DebugScreenUIState,
    onUIAction: (UIAction) -> Unit = {},
) {
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.debug_info_android_version, DeviceUtils.getSDKVersionName()))
        Text(stringResource(R.string.debug_info_api_version, Build.VERSION.SDK_INT))
        Text(stringResource(R.string.debug_info_kernel_version, System.getProperty("os.version")))
        Text(stringResource(R.string.debug_info_brand_model, DeviceUtils.getManufacturer(), DeviceUtils.getModel()))

        if (state.hasOsVibrator) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.debug_info_vibration))
                Button(onClick = { onUIAction(UIAction.TestOSRumble) }) {
                    Text(
                        text = stringResource(R.string.debug_info_test_device_vibration)
                    )
                }
            }
        } else {
            Text(stringResource(R.string.debug_info_vibration) + stringResource(R.string.debug_info_no_vibration_motor))
        }

        DebugInputDevicesComponent(
            state.inputDevicesState,
            onUIAction
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugInputDevicesComponent(
    state: DebugInputDevicesUIState,
    onUIAction: (UIAction) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = state is DebugInputDevicesUIState.Loading,
        onRefresh = { onUIAction(UIAction.RefreshGamepadList) }
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state is DebugInputDevicesUIState.Success) {
                item(state.inputDevices.size) {
                    Text(stringResource(R.string.debug_info_number_of_gamepads, state.inputDevices.size))
                    if (state.inputDevices.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = stringResource(R.string.debug_info_no_gamepad_detected)
                        )
                    }
                }

                items(state.inputDevices) { deviceState ->
                    Column {
                        Text(stringResource(R.string.debug_info_name) + deviceState.name)
                        Text(stringResource(R.string.debug_info_sensors) +
                                when (deviceState.sensorsState) {
                                    SensorUIState.Accelerometer -> stringResource(R.string.debug_info_accelerometer)
                                    SensorUIState.Gyroscope -> stringResource(R.string.debug_info_gyroscope)
                                    SensorUIState.AccelerometerAndGyroscope -> stringResource(R.string.debug_info_accelerometer) + stringResource(R.string.debug_info_gyroscope)
                                    SensorUIState.None -> stringResource(R.string.debug_info_no_relevant_driver)
                                    SensorUIState.ApiBelowAndroid12 -> stringResource(R.string.debug_info_no_api_below_android12)
                                }
                        )
                        if (deviceState.hasRumble) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.debug_info_vibration))
                                Button(onClick = { onUIAction(UIAction.TestGamepadRumble(deviceState.id)) }) {
                                    Text(stringResource(R.string.debug_info_test_gamepad_rumble))
                                }
                            }
                        } else {
                            Text(stringResource(R.string.debug_info_vibration) + stringResource(R.string.debug_info_not_supported))
                        }

                        Text(stringResource(R.string.debug_info_details))
                        Text(deviceState.details)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugSettingsBottomSheet(
    state: DebugSettingsUIState,
    onUIAction: (UIAction) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState()
    if (state.isSettingsShowing) {
        ModalBottomSheet(
            onDismissRequest = {
                onUIAction(UIAction.ToggleShowSettings)
            },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text(stringResource(R.string.debug_info_vibration))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        onClick = { onUIAction(UIAction.ChangeVibrationType(isContinuous = false)) },
                        selected = state.isContinuousVibration.not(),
                        label = { Text(stringResource(R.string.debug_info_one_second)) }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        onClick = { onUIAction(UIAction.ChangeVibrationType(isContinuous = true)) },
                        selected = state.isContinuousVibration,
                        label = { Text(stringResource(R.string.debug_info_continuous)) }
                    )
                }

                var sliderPosition by rememberSaveable { mutableIntStateOf(state.amplitudeForTestVibration) }
                Text(
                    modifier = Modifier.padding(top = 16.dp),
                    text = stringResource(R.string.debug_info_set_amplitude, sliderPosition)
                )
                Slider(
                    modifier = Modifier.padding(top = 8.dp),
                    value = sliderPosition.toFloat(),
                    onValueChange = { sliderPosition = it.roundToInt() },
                    onValueChangeFinished = { onUIAction(UIAction.ChangeVibrationAmplitude(sliderPosition)) },
                    valueRange = 1f..255f,
                    steps = 255,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@ExperimentalMaterial3Api
@Composable
fun DebugInfoScreenPreview() {
    DebugInfoScreen()
}