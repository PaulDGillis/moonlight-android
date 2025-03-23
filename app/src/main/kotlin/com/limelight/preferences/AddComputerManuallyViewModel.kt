package com.limelight.preferences

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.limelight.R
import com.limelight.StringResource
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.KtorClient
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingRepo
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.java.KoinJavaComponent.inject
import java.lang.IllegalArgumentException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class ExternalAddPcRequest(
    val server: String,
    val hostName: String
)

enum class AddPcError {
    WrongSubnetSiteLocalAddress,
    InvalidUserInput,
    NetTestBlocked,
    UnknownError
}

sealed class AddPcState {
    data object Loading : AddPcState()
    data class Success(val uri: Uri) : AddPcState()
    data class Error(val error: AddPcError) : AddPcState()
}

data class AddComputerManuallyUIState(
    val externalAddPcRequest: ExternalAddPcRequest? = null,
    val snackbarMessage: StringResource? = null,
    val addPcState: AddPcState? = null
)

class AddComputerManuallyViewModel : ViewModel(), KoinComponent {

    private val computersToAddChannel = Channel<String>()
    private val managerBindingStateFlow = MutableStateFlow<ComputerManagerBinder?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val addComputerFlow = managerBindingStateFlow.combine(
        computersToAddChannel.receiveAsFlow().flowOn(Dispatchers.IO)
    ) { managerBinder, computerToAdd ->
        doAddPc(managerBinder, computerToAdd).flowOn(Dispatchers.IO)
    }.flatMapConcat { it }

    private val extPairReqStateFlow = MutableStateFlow<ExternalAddPcRequest?>(null)
    private val snackbarMessageStateFlow = MutableStateFlow<StringResource?>(null)

    val state: StateFlow<AddComputerManuallyUIState> = combine(extPairReqStateFlow, snackbarMessageStateFlow, addComputerFlow) { extPairReqState, snackbarMessageState, addComputerResponse ->
        AddComputerManuallyUIState(
            extPairReqState,
            snackbarMessageState,
            addComputerResponse
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddComputerManuallyUIState())

    val provider: LimelightCryptoProvider by inject(LimelightCryptoProvider::class.java)

    init {
        val client = KtorClient(
            "192.168.1.150",
            NvHTTP.DEFAULT_HTTP_PORT,
            uniqueId = "28D06A15-705C-882C-AB0D-81D5E923897E",
            provider
        )
        val pairingRepo = PairingRepo(client)
        val pairingManager = PairingManager(pairingRepo)
        viewModelScope.launch {
            val serverInfo = client.getServerInfo()
            pairingManager.pair(serverInfo, "1234", null)
            println(serverInfo)
        }
    }

    fun onIpChanged(ip: String) {
        val hostAddress = ip.trim()

        if (hostAddress.isEmpty()) {
            snackbarMessageStateFlow.value = StringResource.ById(R.string.addpc_enter_ip)
        } else {
            computersToAddChannel.trySend(hostAddress)
            // TODO Check if more of doAddPc Error handling can be done here rather than in the flow
        }
    }

    fun onComputerServiceConnected(managerBinder: ComputerManagerBinder) {
        managerBindingStateFlow.value = managerBinder
    }

    fun onComputerServiceDisconnected() {
        managerBindingStateFlow.value = null
    }

    @Throws(InterruptedException::class)
    private fun doAddPc(managerBinder: ComputerManagerBinder?, rawUserInput: String?): Flow<AddPcState?> = flow {
        if (managerBinder == null) {
            emit(null)
            return@flow
        }

        emit(AddPcState.Loading)

        val uri = parseRawUserInputToUri(rawUserInput)
        val result = try {

            // Check if we parsed a host address successfully
            if (uri?.host?.isNotBlank() == true) {
                val host = uri.host
                var port = uri.port

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT
                }

                val details = ComputerDetails().apply {
                    manualAddress = AddressTuple(host, port)
                }
                val success = managerBinder.addComputerBlocking(details) == true
                if (!success) {
                    val wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                    if (wrongSiteLocal) {
                        AddPcState.Error(AddPcError.WrongSubnetSiteLocalAddress)
                    } else {
                        AddPcState.Error(AddPcError.UnknownError)
                    }
                } else {
                    AddPcState.Success(uri)
                }
            } else {
                // Invalid user input
                AddPcState.Error(AddPcError.InvalidUserInput)
            }
        } catch (e: InterruptedException) {
            // Propagate the InterruptedException to the caller for proper handling
            AddPcState.Error(AddPcError.UnknownError)
            throw e
        } catch (e: IllegalArgumentException) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace()
            AddPcState.Error(AddPcError.InvalidUserInput)
        }

        // Keep the SpinnerDialog open while testing connectivity
        var portTestResult: Int = if (
            result is AddPcState.Error
            && result.error != AddPcError.WrongSubnetSiteLocalAddress
            && result.error != AddPcError.InvalidUserInput
        ) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            MoonBridge.testClientConnectivity(
                ServerHelper.CONNECTION_TEST_SERVER, 443,
                MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989
            )
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            MoonBridge.ML_TEST_RESULT_INCONCLUSIVE
        }

        emit(
            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                AddPcState.Error(AddPcError.NetTestBlocked)
            } else result
        )
    }

    fun requestPairForHost(incomingPairRequest: ExternalAddPcRequest?) {
        extPairReqStateFlow.value = incomingPairRequest
    }

    fun clearPairRequest() {
        extPairReqStateFlow.value = null
    }

    private fun parseRawUserInputToUri(rawUserInput: String?): Uri? {
        // Try adding a scheme and parsing the remaining input.
        // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
        var uri = "art://$rawUserInput".toUri()
        if (uri.host?.isNotBlank() == true) {
            return uri
        }

        // Attempt to escape the input as an IPv6 literal.
        // This handles input like ::1.
        uri = "art://[$rawUserInput]".toUri()
        if (uri.host?.isNotBlank() == true) {
            return uri
        }

        return null
    }

    private fun isWrongSubnetSiteLocalAddress(address: String?): Boolean {
        try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                return false
            }

            // We have a site-local address. Look for a matching local interface.
            for (iface in Collections.list<NetworkInterface>(NetworkInterface.getNetworkInterfaces())) {
                for (addr in iface.interfaceAddresses) {
                    if (addr.address !is Inet4Address || !addr.address
                            .isSiteLocalAddress
                    ) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue
                    }

                    val targetAddrBytes = targetAddress.address
                    val ifaceAddrBytes = addr.address.address

                    // Compare prefix to ensure it's the same
                    var addressMatches = true
                    for (i in 0 until addr.networkPrefixLength) {
                        if ((ifaceAddrBytes[i / 8].toInt() and (1 shl (i % 8))) != (targetAddrBytes[i / 8].toInt() and (1 shl (i % 8)))) {
                            addressMatches = false
                            break
                        }
                    }

                    if (addressMatches) {
                        return false
                    }
                }
            }

            // Couldn't find a matching interface
            return true
        } catch (e: Exception) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace()
            return false
        }
    }
}