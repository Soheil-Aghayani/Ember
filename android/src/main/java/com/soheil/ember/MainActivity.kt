package com.soheil.ember

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.soheil.ember.engine.VpnState
import com.soheil.ember.ui.EmberRoot
import com.soheil.ember.ui.theme.EmberVpnTheme
import com.soheil.ember.vpn.XrayVpnService

class MainActivity : ComponentActivity() {

    private val engine get() = (application as EmberVpnApp).engine

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else VpnState.error.value = "Ember needs Android's VPN permission to protect this phone."
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun connect() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else startVpn()
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        XrayVpnService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EmberVpnTheme {
                EmberRoot(engine, onConnect = ::connect, onDisconnect = { XrayVpnService.stop(this) })
            }
        }
    }
}
