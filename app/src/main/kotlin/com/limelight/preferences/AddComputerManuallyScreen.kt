package com.limelight.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.R
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    viewModel: AddComputerManuallyViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AddComputerManuallyScreen(state = state, onAddIpClicked = viewModel::onIpChanged)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    state: AddComputerManuallyUIState = AddComputerManuallyUIState(),
    onAddIpClicked: (String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var ip by remember { mutableStateOf("") }

    val context = LocalContext.current

    if (state.snackbarMessage != null) {
        LaunchedEffect(state.snackbarMessage) {
            snackbarHostState.showSnackbar(state.snackbarMessage.format(context), withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }

    val ipTextFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        ipTextFieldFocusRequester.requestFocus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = {
            Text(
                modifier = Modifier.preferKeepClear(),
                text = stringResource(R.string.title_add_pc),
                style = MaterialTheme.typography.titleLarge
            )
        }) }
    ) { contentPadding ->
        Row(
            modifier = Modifier.padding(contentPadding)
                .fillMaxWidth()
                .padding(horizontal = dimensionResource(R.dimen.activity_horizontal_margin)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextField(
                value = ip,
                onValueChange = { ip = it },
                placeholder = { Text(stringResource(R.string.ip_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onAddIpClicked(ip) },
                    // Originally from AddComputerManuallyActivity.java
                    // This is how the Fire TV dismisses the keyboard
                    onPrevious = { onAddIpClicked(ip) },
                ),
                modifier = Modifier.focusRequester(ipTextFieldFocusRequester)
                    .weight(1f)
                    .preferKeepClear()
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter) {
                            onAddIpClicked(ip)
                            true
                        } else false
                    },
            )

            Button(
                modifier = Modifier.preferKeepClear(),
                onClick = { onAddIpClicked(ip) }
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
fun PairPcDialog(
    hostName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = stringResource(R.string.pair_pc_confirm_title)) },
        text = { Text(text = stringResource(R.string.pair_pc_confirm_message, hostName)) },
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.proceed))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun AddComputerManuallyScreenPreview() {
    AddComputerManuallyScreen() {}
}