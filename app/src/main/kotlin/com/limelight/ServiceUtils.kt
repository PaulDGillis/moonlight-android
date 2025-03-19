package com.limelight

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.jvm.java

@Composable
inline fun <reified BoundService : Service, reified BoundServiceBinder : Binder> rememberBoundService(
    crossinline onServiceConnected: @DisallowComposableCalls (BoundServiceBinder) -> Unit,
    crossinline onServiceDisconnected: @DisallowComposableCalls () -> Unit,
) {
    val context: Context = LocalContext.current

    val serviceConnection: ServiceConnection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val boundServiceBinder = service as? BoundServiceBinder ?: return
                onServiceConnected(boundServiceBinder)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                onServiceDisconnected()
            }
        }
    }
    DisposableEffect(context, serviceConnection) {
        context.bindService(Intent(context, BoundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(serviceConnection) }
    }
}