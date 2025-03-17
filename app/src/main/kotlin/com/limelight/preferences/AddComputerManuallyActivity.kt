package com.limelight.preferences

import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.limelight.PcView
import com.limelight.R
import com.limelight.computers.ComputerManagerService
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.coroutineContext
import androidx.core.net.toUri

class AddComputerManuallyActivity : ComponentActivity() {
    private var managerBinder: ComputerManagerBinder? = null

    private val computersToAddChannel = Channel<String>()
    private val serviceConnectionScope = CoroutineScope(Dispatchers.IO)

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, binder: IBinder?) {
            managerBinder = (binder as ComputerManagerBinder?)
            serviceConnectionScope.launch { startAddThread() }
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            serviceConnectionScope.cancel()
            managerBinder = null
        }
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

    private fun parseRawUserInputToUri(rawUserInput: String?): Uri? {
        // Try adding a scheme and parsing the remaining input.
        // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
        var uri = "art://$rawUserInput".toUri()
        if (uri.host != null && !uri.host!!.isEmpty()) {
            return uri
        }

        // Attempt to escape the input as an IPv6 literal.
        // This handles input like ::1.
        uri = "art://[$rawUserInput]".toUri()
        if (uri.host != null && !uri.host!!.isEmpty()) {
            return uri
        }

        return null
    }

    @Throws(InterruptedException::class)
    private fun doAddPc(rawUserInput: String?) {
        var wrongSiteLocal = false
        var invalidInput = false
        var success: Boolean

        val dialog = SpinnerDialog.displayDialog(
            this, resources.getString(R.string.title_add_pc),
            resources.getString(R.string.msg_add_pc), false
        )

        val uri = parseRawUserInputToUri(rawUserInput)
        try {
            val details = ComputerDetails()

            // Check if we parsed a host address successfully
            if (uri != null && uri.host != null && !uri.host!!.isEmpty()) {
                val host = uri.host
                var port = uri.port

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT
                }

                details.manualAddress = AddressTuple(host, port)
                success = managerBinder!!.addComputerBlocking(details)
                if (!success) {
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                }
            } else {
                // Invalid user input
                success = false
                invalidInput = true
            }
        } catch (e: InterruptedException) {
            // Propagate the InterruptedException to the caller for proper handling
            dialog.dismiss()
            throw e
        } catch (e: IllegalArgumentException) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace()
            success = false
            invalidInput = true
        }

        // Keep the SpinnerDialog open while testing connectivity
        var portTestResult: Int = if (!success && !wrongSiteLocal && !invalidInput) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            MoonBridge.testClientConnectivity(
                ServerHelper.CONNECTION_TEST_SERVER, 443,
                MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989
            )
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            MoonBridge.ML_TEST_RESULT_INCONCLUSIVE
        }

        dialog.dismiss()

        if (invalidInput) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_unknown_host),
                false
            )
        } else if (wrongSiteLocal) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_wrong_sitelocal),
                false
            )
        } else if (!success) {
            var dialogText = if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                resources.getString(R.string.nettest_text_blocked)
            } else {
                resources.getString(R.string.addpc_fail)
            }
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                dialogText,
                false
            )
        } else {
            this@AddComputerManuallyActivity.runOnUiThread(object : Runnable {
                override fun run() {
                    Toast.makeText(
                        this@AddComputerManuallyActivity,
                        resources.getString(R.string.addpc_success),
                        Toast.LENGTH_LONG
                    ).show()

                    if (!isFinishing) {
                        // Close the activity
                        this@AddComputerManuallyActivity.finish()
                    }

                    val pin = uri!!.getQueryParameter("pin")
                    val passphrase = uri.getQueryParameter("passphrase")
                    if (pin != null && passphrase != null) {
                        val intent = Intent(this@AddComputerManuallyActivity, PcView::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                        intent.putExtra("hostname", uri.host)
                        intent.putExtra("port", uri.port)
                        intent.putExtra("pin", pin)
                        intent.putExtra("passphrase", passphrase)

                        startActivity(intent)
                    }
                }
            })
        }
    }

    private suspend fun startAddThread() {
        while (coroutineContext.isActive) {
            try {
                val computer = computersToAddChannel.receive()
                doAddPc(computer)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    override fun onStop() {
        super.onStop()

        Dialog.closeDialogs()
        SpinnerDialog.closeDialogs(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (managerBinder != null) {
            serviceConnectionScope.cancel()
            unbindService(serviceConnection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)

        setContent {
            AddComputerManuallyScreen {
                computersToAddChannel.trySend(it)
            }
        }

        // TODO figure out what this is actually doing
        //  UiHelper.notifyNewRootView(this)

        // Bind to the ComputerManager service
        bindService(
            Intent(
                this@AddComputerManuallyActivity,
                ComputerManagerService::class.java
            ), serviceConnection, BIND_AUTO_CREATE
        )


        // Check if we have been called from deep link
        val data = intent.data
        if (data == null) {
            return
        }

        val server = data.authority
        val query = data.query

        // TODO Fix this hostText!!.text = server

        if (query != null && !query.isEmpty()) {
            var hostName = data.getQueryParameter("name")
            hostName = if (hostName != null && !hostName.isEmpty()) {
                "$hostName ($server)"
            } else {
                server
            }

            val builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.pair_pc_confirm_title)
            builder.setMessage(getString(R.string.pair_pc_confirm_message, hostName))

            builder.setPositiveButton(
                getString(R.string.proceed),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    dialog!!.dismiss()
                    finish()
                    computersToAddChannel.trySend("$server?$query")
                })

            builder.setNegativeButton(
                getString(R.string.cancel),
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() })

            val dialog = builder.create()
            dialog.show()
        }
    }
}
