package com.limelight

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.AdapterView.OnItemClickListener
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder
import com.limelight.grid.PcGridAdapter
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManuallyActivity
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings
import com.limelight.ui.AdapterFragment
import com.limelight.ui.AdapterFragmentCallbacks
import com.limelight.utils.Dialog
import com.limelight.utils.HelpLauncher
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import androidx.core.content.edit

class PcView : Activity(), AdapterFragmentCallbacks {
    private var noPcFoundLayout: RelativeLayout? = null
    private var pcGridAdapter: PcGridAdapter? = null
    private var shortcutHelper: ShortcutHelper? = null
    private var managerBinder: ComputerManagerBinder? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var pendingPairingAddress: AddressTuple? = null
    private var pendingPairingPin: String? = null
    private var pendingPairingPassphrase: String? = null
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, binder: IBinder?) {
            val localBinder =
                (binder as ComputerManagerBinder)

            // Wait in a separate thread to avoid stalling the UI
            object : Thread() {
                override fun run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady()

                    // Now make the binder visible
                    managerBinder = localBinder

                    // Start updates
                    startComputerUpdates()

                    // Force a keypair to be generated early to avoid discovery delays
                    AndroidCryptoProvider(this@PcView).clientCertificate
                }
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName?) {
            managerBinder = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews()
        }
    }

    private fun initializeViews() {
        setContentView(R.layout.activity_pc_view)

        UiHelper.notifyNewRootView(this)

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        // Set the correct layout for the PC grid
        pcGridAdapter!!.updateLayoutWithPreferences(
            this,
            PreferenceConfiguration.readPreferences(this)
        )

        // Setup the list view
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val addComputerButton = findViewById<ImageButton>(R.id.manuallyAddPc)
        val helpButton = findViewById<ImageButton>(R.id.helpButton)

        settingsButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                startActivity(Intent(this@PcView, StreamSettings::class.java))
            }
        })
        addComputerButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val i = Intent(this@PcView, AddComputerManuallyActivity::class.java)
                startActivity(i)
            }
        })
        helpButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                HelpLauncher.launchSetupGuide(this@PcView)
            }
        })

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.visibility = View.GONE
        }

        fragmentManager.beginTransaction()
            .replace(R.id.pcFragmentContainer, AdapterFragment())
            .commitAllowingStateLoss()

        noPcFoundLayout = findViewById<RelativeLayout>(R.id.no_pc_found_layout)
        if (pcGridAdapter!!.count == 0) {
            noPcFoundLayout!!.visibility = View.VISIBLE
        } else {
            noPcFoundLayout!!.visibility = View.INVISIBLE
        }
        pcGridAdapter!!.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) {
            val surfaceView = GLSurfaceView(this)
            surfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig?) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER)
                    glPrefs.savedFingerprint = Build.FINGERPRINT
                    glPrefs.writePreferences()

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)

                    runOnUiThread(object : Runnable {
                        override fun run() {
                            completeOnCreate()
                        }
                    })
                }

                override fun onSurfaceChanged(gl10: GL10?, i: Int, i1: Int) {
                }

                override fun onDrawFrame(gl10: GL10?) {
                }
            })
            setContentView(surfaceView)
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer)
            completeOnCreate()
        }

        val intent = getIntent()

        val hostname = intent.getStringExtra("hostname")
        val port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT)
        pendingPairingPin = intent.getStringExtra("pin")
        pendingPairingPassphrase = intent.getStringExtra("passphrase")

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = AddressTuple(hostname, port)
        } else {
            pendingPairingPin = null
            pendingPairingPassphrase = null
        }
    }

    private fun completeOnCreate() {
        completeOnCreateCalled = true

        shortcutHelper = ShortcutHelper(this)

        UiHelper.setLocale(this)

        // Bind to the computer manager service
        bindService(
            Intent(this@PcView, ComputerManagerService::class.java), serviceConnection,
            BIND_AUTO_CREATE
        )

        pcGridAdapter = PcGridAdapter(this, PreferenceConfiguration.readPreferences(this))

        initializeViews()
    }

    private fun startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false
            managerBinder!!.startPolling(object : ComputerManagerListener {
                override fun notifyComputerUpdated(details: ComputerDetails) {
                    if (!freezeUpdates) {
                        this@PcView.runOnUiThread(object : Runnable {
                            override fun run() {
                                updateComputer(details)
                            }
                        })

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper!!.createAppViewShortcutForOnlineHost(details)
                            //                        } else
                        }
                        if (pendingPairingAddress != null) {
                            if (details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress == pendingPairingAddress
                            ) {
                                this@PcView.runOnUiThread(Runnable {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase)
                                    pendingPairingAddress = null
                                    pendingPairingPin = null
                                    pendingPairingPassphrase = null
                                })
                            }
                        }
                    }
                }
            })
            runningPolling = true
        }
    }

    private fun stopComputerUpdates(wait: Boolean) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return
            }

            freezeUpdates = true

            managerBinder!!.stopPolling()

            if (wait) {
                managerBinder!!.waitForPollingStopped()
            }

            runningPolling = false
        }
    }

    public override fun onDestroy() {
        super.onDestroy()

        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    override fun onResume() {
        super.onResume()

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this)

        inForeground = true
        startComputerUpdates()
    }

    override fun onPause() {
        super.onPause()

        inForeground = false
        stopComputerUpdates(false)
    }

    override fun onStop() {
        super.onStop()

        Dialog.closeDialogs()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View?, menuInfo: ContextMenuInfo?) {
        stopComputerUpdates(false)

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo)

        val info = menuInfo as AdapterContextMenuInfo
        val computer = pcGridAdapter!!.getItem(info.position) as ComputerObject

        // Add a header with PC status details
        menu.clearHeader()
        var headerTitle = computer.details.name + " - "
        when (computer.details.state) {
            ComputerDetails.State.ONLINE -> headerTitle += resources.getString(R.string.pcview_menu_header_online)
            ComputerDetails.State.OFFLINE -> {
                menu.setHeaderIcon(R.drawable.ic_pc_offline)
                headerTitle += resources.getString(R.string.pcview_menu_header_offline)
            }

            ComputerDetails.State.UNKNOWN -> headerTitle += resources.getString(R.string.pcview_menu_header_unknown)
        }

        menu.setHeaderTitle(headerTitle)

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN
        ) {
            menu.add(Menu.NONE, WOL_ID, 1, resources.getString(R.string.pcview_menu_send_wol))
            menu.add(
                Menu.NONE,
                GAMESTREAM_EOL_ID,
                2,
                resources.getString(R.string.pcview_menu_eol)
            )
        } else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(
                Menu.NONE,
                PAIR_ID_OTP,
                1,
                resources.getString(R.string.pcview_menu_pair_pc_otp)
            )
            menu.add(Menu.NONE, PAIR_ID, 2, resources.getString(R.string.pcview_menu_pair_pc))
            if (computer.details.nvidiaServer) {
                menu.add(
                    Menu.NONE,
                    GAMESTREAM_EOL_ID,
                    3,
                    resources.getString(R.string.pcview_menu_eol)
                )
            } else {
                menu.add(
                    Menu.NONE,
                    OPEN_MANAGEMENT_PAGE_ID,
                    3,
                    resources.getString(R.string.pcview_menu_open_management_page)
                )
            }
        } else {
            if (computer.details.runningGameId != 0) {
                menu.add(
                    Menu.NONE,
                    RESUME_ID,
                    1,
                    resources.getString(R.string.applist_menu_resume)
                )
                menu.add(
                    Menu.NONE,
                    QUIT_ID,
                    2,
                    resources.getString(R.string.applist_menu_quit)
                )
            }

            if (computer.details.nvidiaServer) {
                menu.add(
                    Menu.NONE,
                    GAMESTREAM_EOL_ID,
                    3,
                    resources.getString(R.string.pcview_menu_eol)
                )
            } else {
                menu.add(
                    Menu.NONE,
                    OPEN_MANAGEMENT_PAGE_ID,
                    3,
                    resources.getString(R.string.pcview_menu_open_management_page)
                )
            }

            menu.add(
                Menu.NONE,
                FULL_APP_LIST_ID,
                4,
                resources.getString(R.string.pcview_menu_app_list)
            )
        }

        menu.add(
            Menu.NONE,
            TEST_NETWORK_ID,
            5,
            resources.getString(R.string.pcview_menu_test_network)
        )
        menu.add(Menu.NONE, DELETE_ID, 6, resources.getString(R.string.pcview_menu_delete_pc))
        menu.add(
            Menu.NONE,
            VIEW_DETAILS_ID,
            7,
            resources.getString(R.string.pcview_menu_details)
        )
    }

    override fun onContextMenuClosed(menu: Menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates()
    }

    private fun doPair(computer: ComputerDetails, otp: String?, passphrase: String?) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.pair_pc_offline),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.error_manager_not_running),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this@PcView, resources.getString(R.string.pairing), Toast.LENGTH_SHORT)
            .show()
        Thread(object : Runnable {
            override fun run() {
                val httpConn: NvHTTP?
                var message: String?
                var success = false
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true)

                    httpConn = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder!!.uniqueId, computer.serverCert,
                        PlatformBinding.getCryptoProvider(this@PcView)
                    )
                    if (httpConn.pairState == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null
                        success = true
                    } else {
                        var pinStr = otp
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString()
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(
                                this@PcView, resources.getString(R.string.pair_pairing_title),
                                resources.getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                        resources.getString(R.string.pair_pairing_help), false
                            )
                        } else {
                            Dialog.displayDialog(
                                this@PcView,
                                resources.getString(R.string.pair_pairing_title),
                                resources.getString(R.string.pair_otp_pairing_msg) + "\n\n" +
                                        resources.getString(R.string.pair_otp_pairing_help),
                                false
                            )
                        }

                        val pm = httpConn.pairingManager

                        val pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase)
                        if (pairState == PairState.PIN_WRONG) {
                            message = resources.getString(R.string.pair_incorrect_pin)
                        } else if (pairState == PairState.FAILED) {
                            message = if (computer.runningGameId != 0) {
                                resources.getString(R.string.pair_pc_ingame)
                            } else {
                                resources.getString(R.string.pair_fail)
                            }
                        } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = resources.getString(R.string.pair_already_in_progress)
                        } else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null
                            success = true

                            // Pin this certificate for later HTTPS use
                            managerBinder!!.getComputer(computer.uuid).serverCert =
                                pm.pairedCert

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder!!.invalidateStateForComputer(computer.uuid)
                        } else {
                            // Should be no other values
                            message = null
                        }
                    }
                } catch (_: UnknownHostException) {
                    message = resources.getString(R.string.error_unknown_host)
                } catch (_: FileNotFoundException) {
                    message = resources.getString(R.string.error_404)
                } catch (e: XmlPullParserException) {
                    e.printStackTrace()
                    message = e.message
                } catch (e: IOException) {
                    e.printStackTrace()
                    message = e.message
                }

                Dialog.closeDialogs()

                val toastMessage = message
                val toastSuccess = success
                runOnUiThread(object : Runnable {
                    override fun run() {
                        if (toastMessage != null) {
                            Toast.makeText(this@PcView, toastMessage, Toast.LENGTH_LONG).show()
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false)
                        } else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates()
                        }
                    }
                })
            }
        }).start()
    }

    private fun doOTPPair(computer: ComputerDetails) {
        val context: Context = this@PcView

        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 40)

        val otpInput = EditText(context)
        otpInput.hint = "PIN"
        otpInput.inputType = InputType.TYPE_CLASS_NUMBER
        otpInput.filters = arrayOf<InputFilter>(LengthFilter(4))

        val passphraseInput = EditText(context)
        passphraseInput.hint = getString(R.string.pair_passphrase_hint)
        passphraseInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        layout.addView(otpInput)
        layout.addView(passphraseInput)

        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp)
        dialogBuilder.setView(layout)

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null)

        dialogBuilder.setNegativeButton(
            getString(R.string.cancel),
            DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int -> dialog!!.dismiss() })
        val dialog = dialogBuilder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(View.OnClickListener { v: View? ->
                val pin = otpInput.text.toString()
                val passphrase = passphraseInput.text.toString()
                if (pin.length != 4) {
                    Toast.makeText(
                        context,
                        getString(R.string.pair_pin_length_msg),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@OnClickListener
                }
                if (passphrase.length < 4) {
                    Toast.makeText(
                        context,
                        getString(R.string.pair_passphrase_length_msg),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@OnClickListener
                }
                doPair(computer, pin, passphrase)
                dialog.dismiss() // Manually dismiss the dialog if the input is valid
            })
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.wol_pc_online),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (computer.macAddress == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.wol_no_mac),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Thread(object : Runnable {
            override fun run() {
                var message: String
                try {
                    WakeOnLanSender.sendWolPacket(computer)
                    message = resources.getString(R.string.wol_waking_msg)
                } catch (_: IOException) {
                    message = resources.getString(R.string.wol_fail)
                }

                val toastMessage = message
                runOnUiThread(object : Runnable {
                    override fun run() {
                        Toast.makeText(this@PcView, toastMessage, Toast.LENGTH_LONG).show()
                    }
                })
            }
        }).start()
    }

    private fun doUnpair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.error_pc_offline),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.error_manager_not_running),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this@PcView,
            resources.getString(R.string.unpairing),
            Toast.LENGTH_SHORT
        ).show()
        Thread(object : Runnable {
            override fun run() {
                val httpConn: NvHTTP?
                var message: String?
                try {
                    httpConn = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder!!.uniqueId, computer.serverCert,
                        PlatformBinding.getCryptoProvider(this@PcView)
                    )
                    message = if (httpConn.pairState == PairState.PAIRED) {
                        httpConn.unpair()
                        if (httpConn.pairState == PairState.NOT_PAIRED) {
                            resources.getString(R.string.unpair_success)
                        } else {
                            resources.getString(R.string.unpair_fail)
                        }
                    } else {
                        resources.getString(R.string.unpair_error)
                    }
                } catch (_: UnknownHostException) {
                    message = resources.getString(R.string.error_unknown_host)
                } catch (_: FileNotFoundException) {
                    message = resources.getString(R.string.error_404)
                } catch (e: XmlPullParserException) {
                    message = e.message
                    e.printStackTrace()
                } catch (e: IOException) {
                    message = e.message
                    e.printStackTrace()
                }

                val toastMessage = message
                runOnUiThread(object : Runnable {
                    override fun run() {
                        Toast.makeText(this@PcView, toastMessage, Toast.LENGTH_LONG).show()
                    }
                })
            }
        }).start()
    }

    private fun doAppList(
        computer: ComputerDetails,
        newlyPaired: Boolean,
        showHiddenGames: Boolean
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.error_pc_offline),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (managerBinder == null) {
            Toast.makeText(
                this@PcView,
                resources.getString(R.string.error_manager_not_running),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val i = Intent(this, AppView::class.java)
        i.putExtra(AppView.NAME_EXTRA, computer.name)
        i.putExtra(AppView.UUID_EXTRA, computer.uuid)
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)
        startActivity(i)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo?
        val computer = pcGridAdapter!!.getItem(info!!.position) as ComputerObject
        when (item.itemId) {
            PAIR_ID -> {
                doPair(computer.details, null, null)
                return true
            }

            PAIR_ID_OTP -> {
                doOTPPair(computer.details)
                return true
            }

            UNPAIR_ID -> {
                doUnpair(computer.details)
                return true
            }

            WOL_ID -> {
                doWakeOnLan(computer.details)
                return true
            }

            DELETE_ID -> {
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey")
                    return true
                }
                UiHelper.displayDeletePcConfirmationDialog(
                    this,
                    computer.details,
                    object : Runnable {
                        override fun run() {
                            if (managerBinder == null) {
                                Toast.makeText(
                                    this@PcView,
                                    resources.getString(R.string.error_manager_not_running),
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }
                            removeComputer(computer.details)
                        }
                    },
                    null
                )
                return true
            }

            FULL_APP_LIST_ID -> {
                doAppList(computer.details, false, true)
                return true
            }

            RESUME_ID -> {
                if (managerBinder == null) {
                    Toast.makeText(
                        this@PcView,
                        resources.getString(R.string.error_manager_not_running),
                        Toast.LENGTH_LONG
                    ).show()
                    return true
                }

                ServerHelper.doStart(
                    this,
                    NvApp("app", computer.details.runningGameId, false),
                    computer.details,
                    managerBinder,
                    false
                )
                return true
            }

            QUIT_ID -> {
                if (managerBinder == null) {
                    Toast.makeText(
                        this@PcView,
                        resources.getString(R.string.error_manager_not_running),
                        Toast.LENGTH_LONG
                    ).show()
                    return true
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, object : Runnable {
                    override fun run() {
                        ServerHelper.doQuit(
                            this@PcView, computer.details,
                            NvApp("app", 0, false), managerBinder, null
                        )
                    }
                }, null)
                return true
            }

            VIEW_DETAILS_ID -> {
                Dialog.displayDialog(
                    this@PcView,
                    resources.getString(R.string.title_details),
                    computer.details.toString(),
                    false
                )
                return true
            }

            TEST_NETWORK_ID -> {
                ServerHelper.doNetworkTest(this@PcView)
                return true
            }

            GAMESTREAM_EOL_ID -> {
                HelpLauncher.launchGameStreamEolFaq(this@PcView)
                return true
            }

            OPEN_MANAGEMENT_PAGE_ID -> {
                val managementUrl = computer.guessManagementUrl()
                if (managementUrl == null) {
                    Toast.makeText(
                        this@PcView,
                        resources.getString(R.string.pcview_error_no_management_url),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    HelpLauncher.launchUrl(this@PcView, managementUrl)
                }

                return super.onContextItemSelected(item)
            }

            else -> return super.onContextItemSelected(item)
        }
    }

    private fun removeComputer(details: ComputerDetails) {
        managerBinder!!.removeComputer(details)

        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid)

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit {
                remove(details.uuid)
            }

        for (i in 0..<pcGridAdapter!!.count) {
            val computer = pcGridAdapter!!.getItem(i) as ComputerObject

            if (details == computer.details) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper!!.disableComputerShortcut(
                    details,
                    resources.getString(R.string.scut_deleted_pc)
                )

                pcGridAdapter!!.removeComputer(computer)
                pcGridAdapter!!.notifyDataSetChanged()

                if (pcGridAdapter!!.count == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout!!.visibility = View.VISIBLE
                }

                break
            }
        }
    }

    private fun updateComputer(details: ComputerDetails) {
        var existingEntry: ComputerObject? = null

        for (i in 0..<pcGridAdapter!!.count) {
            val computer = pcGridAdapter!!.getItem(i) as ComputerObject

            // Check if this is the same computer
            if (details.uuid == computer.details.uuid) {
                existingEntry = computer
                break
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details
        } else {
            // Add a new entry
            pcGridAdapter!!.addComputer(ComputerObject(details))

            // Remove the "Discovery in progress" view
            noPcFoundLayout!!.visibility = View.INVISIBLE
        }

        // Notify the view that the data has changed
        pcGridAdapter!!.notifyDataSetChanged()
    }

    override fun getAdapterFragmentLayoutId(): Int {
        return R.layout.pc_grid_view
    }

    override fun receiveAbsListView(listView: AbsListView) {
        listView.adapter = pcGridAdapter
        listView.onItemClickListener = object : OnItemClickListener {
            override fun onItemClick(
                arg0: AdapterView<*>?, arg1: View?, pos: Int,
                id: Long
            ) {
                val computer = pcGridAdapter!!.getItem(pos) as ComputerObject
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE
                ) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1)
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details, null, null)
                } else {
                    doAppList(computer.details, false, false)
                }
            }
        }
        UiHelper.applyStatusBarPadding(listView)
        registerForContextMenu(listView)
    }

    class ComputerObject(details: ComputerDetails) {
        @JvmField
        var details: ComputerDetails

        init {
            requireNotNull(details) { "details must not be null" }
            this.details = details
        }

        override fun toString(): String {
            return details.name
        }

        fun guessManagementUrl(): String? {
            if (details.activeAddress == null) return null
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1)
        }
    }

    companion object {
        private const val PAIR_ID = 2
        private const val UNPAIR_ID = 3
        private const val WOL_ID = 4
        private const val DELETE_ID = 5
        private const val RESUME_ID = 6
        private const val QUIT_ID = 7
        private const val VIEW_DETAILS_ID = 8
        private const val FULL_APP_LIST_ID = 9
        private const val TEST_NETWORK_ID = 10
        private const val GAMESTREAM_EOL_ID = 11
        private const val OPEN_MANAGEMENT_PAGE_ID = 20
        private const val PAIR_ID_OTP = 21
    }
}
