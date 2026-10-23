package com.soheil.ember

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.soheil.ember.data.ControlPlaneHttp
import com.soheil.ember.data.CrashReporter
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.GuardianClient
import com.soheil.ember.data.ProxyStateStore
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.SettingsStore
import com.soheil.ember.data.TokenStore
import com.soheil.ember.vpn.EmberVpnService
import com.soheil.ember.vpn.upstream.NettyLoggingBridge
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

        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        NettyLoggingBridge.install()
        installConscrypt()
        UpstreamTrust.init(this)
        tokenStore = TokenStore(this)
        proxyStateStore = ProxyStateStore(this)
        settingsStore = SettingsStore(this)
        ControlPlaneHttp.tlsSplitModeProvider = { settingsStore.tlsSplitMode }
        guardianClient = GuardianClient()
        serverListClient = ServerListClient(this)
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
