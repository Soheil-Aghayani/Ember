package com.soheil.ember

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.LogSink
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.VpnState
import com.soheil.ember.widget.ConnectWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class EmberVpnApp : Application() {

    /** The servers, their measurements and the user's choices; shared by the screens and the VPN service. */
    val engine: AndroidEngine by lazy { AndroidEngine(this) }

    override fun onCreate() {
        super.onCreate()
        AppLogger.sink = AndroidLogSink
        createNotificationChannel()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            merge(
                VpnState.phase.map { Unit },
                VpnState.node.map { Unit },
                VpnState.progress.map { Unit },
                VpnState.error.map { Unit },
                engine.selectedId.map { Unit },
                engine.language.map { Unit },
            ).collect { ConnectWidget.refresh(this@EmberVpnApp) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(VPN_NOTIFICATION_CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "ember_status"
    }
}

private object AndroidLogSink : LogSink {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }
    override fun e(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}
