package com.limelight.preferences

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.limelight.PcView
import com.limelight.R
import com.limelight.utils.UiHelper

class AddComputerManuallyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UiHelper.setLocale(this)

        // TODO figure out what this is actually doing
        //  UiHelper.notifyNewRootView(this)

        // Check if we have been called from deep link
        val data = intent.data
        val server = data?.authority

        val hostReq = if (data?.query?.isNotEmpty() == true && server != null) {
            val hostName = data.getQueryParameter("name").let { name ->
                if (name?.isNotBlank() == true)
                    "$name ($server)"
                else
                    server
            }
            ExternalAddPcRequest(server, hostName)
        } else null

        setContent {
            AddComputerManuallyScreen(hostReq) { uri: Uri ->
                Toast.makeText(
                    this@AddComputerManuallyActivity,
                    resources.getString(R.string.addpc_success),
                    Toast.LENGTH_LONG
                ).show()

                if (!isFinishing) {
                    // Close the activity
                    this@AddComputerManuallyActivity.finish()
                }

                val pin = uri.getQueryParameter("pin")
                val passphrase = uri.getQueryParameter("passphrase")
                if (pin != null && passphrase != null) {
                    val intent = Intent(this@AddComputerManuallyActivity, PcView::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("hostname", uri.host)
                        putExtra("port", uri.port)
                        putExtra("pin", pin)
                        putExtra("passphrase", passphrase)
                    }

                    startActivity(intent)
                }
            }
        }
    }
}
