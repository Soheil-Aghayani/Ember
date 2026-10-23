package com.soheil.ember

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.ControlPlaneHttp
import com.soheil.ember.data.CrashReporter
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.GuardianClient
import com.soheil.ember.data.LogSink
import com.soheil.ember.data.ProxyStateStore
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.SettingsStore
import com.soheil.ember.data.TokenStore
import com.soheil.ember.vpn.EmberVpnService
import com.soheil.ember.vpn.upstream.NettyLoggingBridge
import com.soheil.ember.vpn.upstream.UpstreamDebug
import com.soheil.ember.vpn.upstream.UpstreamTrust
import com.soheil.ember.widget.QuickConnectWidgetProvider
import org.conscrypt.Conscrypt
import java.security.Security

class EmberVpnApp : Application() {

    lateinit var tokenStore: TokenStore
    lateinit var proxyStateStore: ProxyStateStore
    lateinit var settingsStore: SettingsStore
    lateinit var guardianClient: GuardianClient
    lateinit var serverListClient: ServerListClient
    lateinit var authRepository: FxaAuthRepository

    override fun onCreate() {
        super.onCreate()

        AppLogger.sink = AndroidLogSink
        UpstreamDebug.frameLogging = BuildConfig.DEBUG
        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        NettyLoggingBridge.install()
        installConscrypt()
        UpstreamTrust.init { resources.openRawResource(R.raw.globalsign_roots) }
        tokenStore = TokenStore(this)
        proxyStateStore = ProxyStateStore(this)
        settingsStore = SettingsStore(this)
        ControlPlaneHttp.tlsSplitModeProvider = { settingsStore.tlsSplitMode }
        guardianClient = GuardianClient()
        serverListClient = ServerListClient(filesDir)
        authRepository = FxaAuthRepository(tokenStore)
        createNotificationChannel()
    }

    private fun installConscrypt() {
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure {
            Log.w("EmberVpnApp", "failed to install Conscrypt security provider", it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            VPN_NOTIFICATION_CHANNEL_ID,
            "VPN status",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        QuickConnectWidgetProvider.updateAll(this, EmberVpnService.state.value)
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
