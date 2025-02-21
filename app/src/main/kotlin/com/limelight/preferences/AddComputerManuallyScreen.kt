package com.limelight.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.unit.dp
import com.limelight.R
import kotlinx.coroutines.launch

@Composable
fun AddComputerManuallyScreen(
    modifier: Modifier = Modifier,
    onIpChanged: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ipTextFieldFocusRequester = remember { FocusRequester() }
    var ip by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ipTextFieldFocusRequester.requestFocus()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        Column(
            modifier = modifier.padding(contentPadding)
                .fillMaxSize()
                .padding(
                    vertical = dimensionResource(R.dimen.activity_vertical_margin),
                    horizontal = dimensionResource(R.dimen.activity_horizontal_margin)
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                modifier = Modifier.preferKeepClear(),
                text = stringResource(R.string.title_add_pc),
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        onDone = { handleDoneEvent(ip) },
                        // Originally from AddComputerManuallyActivity.java
                        // This is how the Fire TV dismisses the keyboard
                        onPrevious = { handleDoneEvent(ip) },
                    ),
                    modifier = Modifier.focusRequester(ipTextFieldFocusRequester)
                        .weight(1f)
                        .preferKeepClear()
                        .padding(top = 25.dp)
                        .onPreviewKeyEvent {
                            if (it.key == Key.Enter) {
                                handleDoneEvent(ip)
                                true
                            } else false
                        },
                )

                Button(
                    modifier = Modifier.preferKeepClear().padding(top = 25.dp),
                    onClick = {}
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AddComputerManuallyScreenPreview() {
    AddComputerManuallyScreen()
}