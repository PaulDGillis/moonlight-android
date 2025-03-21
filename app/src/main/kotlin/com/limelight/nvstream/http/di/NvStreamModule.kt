package com.limelight.nvstream.http.di

import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.LimelightCryptoProvider
import org.koin.dsl.module

val nvstreamModule = module {
    factory<LimelightCryptoProvider> {
        AndroidCryptoProvider(get())
    }
}