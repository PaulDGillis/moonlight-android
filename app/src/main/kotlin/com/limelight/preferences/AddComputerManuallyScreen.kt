package com.limelight.preferences

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.preferKeepClear
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.R
import com.limelight.computers.ComputerManagerService
import com.limelight.rememberBoundService
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    incomingPairPcRequest: ExternalAddPcRequest? = null,
    viewModel: AddComputerManuallyViewModel = koinViewModel(),
    onNavigateToPcScreen: (Uri) -> Unit
) {
    rememberBoundService<ComputerManagerService, ComputerManagerService.ComputerManagerBinder>(
        onServiceConnected = viewModel::onComputerServiceConnected,
        onServiceDisconnected = viewModel::onComputerServiceDisconnected
    )

    LaunchedEffect(incomingPairPcRequest) {
        viewModel.requestPairForHost(incomingPairPcRequest)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    AddComputerManuallyScreen(
        state,
        onNavigateToPcScreen,
        viewModel::onIpChanged,
        viewModel::clearPairRequest
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerManuallyScreen(
    state: AddComputerManuallyUIState = AddComputerManuallyUIState(),
    onNavigateToPcScreen: (Uri) -> Unit = {},
    onAddIpClicked: (String) -> Unit = {},
    onClearRequest: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    if (state.snackbarMessage != null) {
        val context = LocalContext.current
        LaunchedEffect(state.snackbarMessage) {
            snackbarHostState.showSnackbar(state.snackbarMessage.format(context), withDismissAction = true, duration = SnackbarDuration.Long)
        }
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
        when (val pcState = state.addPcState) {
            is AddPcState.Success -> onNavigateToPcScreen(pcState.uri)
            AddPcState.Loading -> AddPcLoading(Modifier.padding(contentPadding))
            else -> {
                Box(Modifier.padding(contentPadding)) {
                    AddPcTextField(state.externalAddPcRequest?.server, onAddIpClicked)
                    if (pcState is AddPcState.Error) {
                        PairPcErrorDialog(Modifier.padding(contentPadding), pcState.error)
                    }

                    if (state.externalAddPcRequest != null) {
                        PairPcDialog(
                            state.externalAddPcRequest.hostName,
                            onConfirm = {
                                onAddIpClicked(state.externalAddPcRequest.server)
                                onClearRequest()
                            },
                            onCancel = { onClearRequest }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddPcTextField(
    initialIp: String?,
    onAddIpClicked: (String) -> Unit,
) {
    var ip by remember { mutableStateOf(initialIp ?: "") }
    val ipTextFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        ipTextFieldFocusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = dimensionResource(R.dimen.activity_horizontal_margin)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
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

@Composable
fun AddPcLoading(
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.defaultMinSize(80.dp, 80.dp)
        )
        Text(
            stringResource(R.string.msg_add_pc),
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
fun PairPcErrorDialog(
    modifier: Modifier,
    error: AddPcError
) {
    AlertDialog(
        modifier = modifier,
        title = { Text(stringResource(R.string.conn_error_title)) },
        text = { Text(
            stringResource(
                when (error) {
                    AddPcError.WrongSubnetSiteLocalAddress -> R.string.addpc_wrong_sitelocal
                    AddPcError.InvalidUserInput -> R.string.addpc_unknown_host
                    AddPcError.NetTestBlocked -> R.string.nettest_text_blocked
                    AddPcError.UnknownError -> R.string.addpc_fail
                }
            )
        ) },
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = {},
    )
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
    AddComputerManuallyScreen(
        state = AddComputerManuallyUIState()
    )
}