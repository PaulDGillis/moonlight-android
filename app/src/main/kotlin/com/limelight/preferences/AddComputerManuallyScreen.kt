package com.limelight.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    viewModel: AddComputerManuallyViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    state: AddComputerManuallyUIState = AddComputerManuallyUIState(),
    onIpChanged: (String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var ip by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    if (state.snackbarMessage != null) {
        LaunchedEffect(state.snackbarMessage) {
            snackbarHostState.showSnackbar("", withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }


    fun handleDoneEvent(rawUserInput: String) {
        val hostAddress = rawUserInput.trim()

        if (hostAddress.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.addpc_enter_ip),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long
                )
            }
        } else {
            onIpChanged.invoke(hostAddress)
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
                    onDone = { onIpChanged(ip) },
                    // Originally from AddComputerManuallyActivity.java
                    // This is how the Fire TV dismisses the keyboard
                    onPrevious = { onIpChanged(ip) },
                ),
                modifier = Modifier.focusRequester(ipTextFieldFocusRequester)
                    .weight(1f)
                    .preferKeepClear()
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter) {
                            onIpChanged(ip)
                            true
                        } else false
                    },
            )

            Button(
                modifier = Modifier.preferKeepClear(),
                onClick = { onIpChanged(ip) }
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AddComputerManuallyScreenPreview() {
    AddComputerManuallyScreen() {}
}