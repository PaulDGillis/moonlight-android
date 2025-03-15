package com.limelight.debug

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.R
import com.limelight.utils.DeviceUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

enum class RumbleType {
    OneSecond,
    Continuous
}

sealed class UIAction {
    object RefreshGamepadList : UIAction()
    data class ChangeVibrationAmplitude(val amplitude: Int) : UIAction()
    data class TestOSRumble(val rumbleType: RumbleType) : UIAction()
    data class TestGamepadRumble(val deviceId: Int, val rumbleType: RumbleType) : UIAction()
    object CancelRumble : UIAction()
}

@ExperimentalMaterial3Api
@Composable
fun DebugInfoScreen(
    modifier: Modifier = Modifier,
    viewModel: DebugInfoViewModel = koinViewModel()
) {
    val state by viewModel.debugInfoStateFlow.collectAsStateWithLifecycle()
    DebugInfoScreen(
        modifier = modifier,
        state = state,
        onUIAction = { action ->
            when (action) {
                UIAction.RefreshGamepadList -> viewModel.refreshGamePadList()
                is UIAction.TestOSRumble -> viewModel.rumble(rumbleType = action.rumbleType)
                is UIAction.TestGamepadRumble -> viewModel.rumble(action.deviceId, action.rumbleType)
                is UIAction.ChangeVibrationAmplitude -> viewModel.changePlatformVibrationAmplitude(action.amplitude)
                UIAction.CancelRumble -> viewModel.cancelRumble()
            }
        }
    )
}

@ExperimentalMaterial3Api
@Composable
private fun DebugInfoScreen(
    modifier: Modifier = Modifier,
    state: DebugPlatformState,
    onUIAction: (UIAction) -> Unit = {},
) {
    Column(
        modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val options = mapOf<RumbleType, String>(
            RumbleType.OneSecond to stringResource(R.string.debug_info_simple_vibration),
            RumbleType.Continuous to stringResource(R.string.debug_info_continuous_hd_vibration)
        )
        var expanded by rememberSaveable { mutableStateOf(false) }
        var rumbleTypeState = remember { mutableStateOf(options.entries.first()) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            TextField(
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                value = rumbleTypeState.value.value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = @Composable { Text(stringResource(R.string.debug_info_please_choose)) },
                trailingIcon = @Composable { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption.value) },
                        onClick = { rumbleTypeState.value = selectionOption }
                    )
                }
            }
        }
        Button(onClick = { onUIAction(UIAction.TestOSRumble(rumbleTypeState.value.key)) }) {
            Text(
                text = stringResource(R.string.debug_info_test_device_vibration,
                    stringResource(
                        if (state.hasVibrator) R.string.debug_info_has_vibration_motor
                        else R.string.debug_info_no_vibration_motor
                    )
                )
            )
        }

        var sliderPosition by rememberSaveable { mutableIntStateOf(state.testVibrationAmplitude) }
        Text(stringResource(R.string.debug_info_set_amplitude) + " ($sliderPosition)")
        Slider(
            modifier = Modifier.padding(10.dp),
            value = sliderPosition.toFloat(),
            onValueChange = { sliderPosition = it.roundToInt() },
            onValueChangeFinished = { onUIAction(UIAction.ChangeVibrationAmplitude(sliderPosition)) },
            valueRange = 1f..255f,
            steps = 255,
        )

        Button(onClick = { onUIAction(UIAction.CancelRumble) }) {
            Text(text = stringResource(R.string.debug_info_stop_vibration))
        }

        Text(stringResource(R.string.debug_info_android_version) + DeviceUtils.getSDKVersionName())
        Text(stringResource(R.string.debug_info_api_version) + Build.VERSION.SDK_INT)
        Text(stringResource(R.string.debug_info_kernel_version) + System.getProperty("os.version"))
        Text(stringResource(R.string.debug_info_brand_model) + DeviceUtils.getManufacturer() + "\t-\t" + DeviceUtils.getModel())

        Button(onClick = { onUIAction(UIAction.RefreshGamepadList) }) {
            Text(text = stringResource(R.string.debug_info_refresh_gamepad_list))
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(state.inputDevices.size) {
                Text(stringResource(R.string.debug_info_number_of_gamepads) + " " + state.inputDevices.size)
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
                        Row {
                            Text(stringResource(R.string.debug_info_vibration) + stringResource(R.string.debug_info_supported))
                            Button(onClick = { onUIAction(UIAction.TestGamepadRumble(deviceState.id, rumbleTypeState.value.key)) }) {
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

@Preview(showBackground = true)
@ExperimentalMaterial3Api
@Composable
fun DebugInfoScreenPreview() {
    DebugInfoScreen()
}