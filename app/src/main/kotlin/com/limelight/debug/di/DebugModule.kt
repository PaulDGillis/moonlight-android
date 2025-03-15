package com.limelight.debug.di

import android.content.Context
import android.content.Context.VIBRATOR_MANAGER_SERVICE
import android.content.Context.VIBRATOR_SERVICE
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.limelight.debug.DebugInfoViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val debugModule = module {
    viewModel {
        val context: Context = get()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        DebugInfoViewModel(vibrator)
    }
}