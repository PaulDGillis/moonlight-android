package com.limelight

import android.app.Application
import com.limelight.debug.di.debugModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class MoonKnightApplication: Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@MoonKnightApplication)
            modules(debugModule)
        }
    }
}