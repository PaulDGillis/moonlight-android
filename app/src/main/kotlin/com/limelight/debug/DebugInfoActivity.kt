package com.limelight.debug

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults.contentPadding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.compose.MKAppTheme

class DebugInfoActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MKAppTheme {
                Scaffold { contentPadding ->
                    DebugInfoScreen(modifier = Modifier.padding(contentPadding))
                }
            }
        }
    }

//    fun onClick(v: View) {
//        // Gamepad Vibration
//        if (v.id == R.id.bt_vibrator_gamepad) {
//            if (ids.isEmpty()) {
//                Toast.makeText(
//                    this@DebugInfoActivity,
//                    getString(R.string.debug_info_no_gamepad_detected),
//                    Toast.LENGTH_LONG
//                ).show()
//                return
//            }
//            val strings = ids.map { it?.name }.toTypedArray()
//            AlertDialog.Builder(this).setItems(strings, object : DialogInterface.OnClickListener {
//                override fun onClick(dialog: DialogInterface, which: Int) {
//                    dialog.dismiss()
//                    if (ids[which]!!.getVibrator().hasVibrator()) {
//                        val titles = arrayOf<String>(
//                            getString(R.string.debug_info_simple_vibration),
//                            getString(R.string.debug_info_continuous_hd_vibration)
//                        )
//                        AlertDialog.Builder(this@DebugInfoActivity)
//                            .setItems(titles, object : DialogInterface.OnClickListener {
//                                override fun onClick(dialog: DialogInterface, which2: Int) {
//                                    dialog.dismiss()
//                                    when (which2) {
//                                        0 -> ids[which]!!.getVibrator().vibrate(1000)
//                                        1 -> {
//                                            cancelRumble()
//                                            vibratorOnline = ids[which]!!.getVibrator()
//                                            rumble(vibratorOnline!!)
//                                        }
//                                    }
//                                }
//                            }).setTitle(getString(R.string.debug_info_please_choose)).create()
//                            .show()
//                    } else {
//                        Toast.makeText(
//                            this@DebugInfoActivity,
//                            getString(R.string.debug_info_no_vibrator),
//                            Toast.LENGTH_SHORT
//                        ).show()
//                    }
//                }
//            }).setTitle(getString(R.string.debug_info_please_choose)).create().show()
//            return
//        }
//    }
}
