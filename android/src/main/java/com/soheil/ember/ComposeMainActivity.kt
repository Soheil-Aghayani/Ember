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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.LayoutDirection
import com.soheil.ember.ui.EmberRoot
import com.soheil.ember.ui.LocalStrings
import com.soheil.ember.ui.PersianStrings
import com.soheil.ember.ui.stringsFor
import com.soheil.ember.ui.theme.EmberVpnTheme
import com.soheil.ember.vpn.XrayVpnService

class ComposeMainActivity : ComponentActivity() {

    private val engine get() = (application as EmberVpnApp).engine

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else VpnState.error.value = engine.strings.vpnPermission
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
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || BuildConfig.TV_PREVIEW
        setContent {
            val language by engine.language.collectAsState()
            val words = stringsFor(language)
            EmberVpnTheme(layoutDirection = if (words === PersianStrings) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CompositionLocalProvider(LocalStrings provides words) {
                    EmberRoot(engine, onConnect = ::connect, onDisconnect = { XrayVpnService.stop(this) }, isTv = isTv)
                }
            }
        }
    }
}
