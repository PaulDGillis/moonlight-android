package com.limelight.preferences.di

import com.limelight.preferences.AddComputerManuallyViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val preferencesModule = module {
    viewModelOf(::AddComputerManuallyViewModel)
}