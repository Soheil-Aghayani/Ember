package com.soheil.ember.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.soheil.ember.MainActivity
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A quick-settings tile that connects and disconnects. If Android's VPN permission has not been given yet, it opens Ember. */
class QuickConnectTile : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        job = scope.launch {
            combine(VpnState.phase, VpnState.node) { phase, node -> phase to node }.collect { (phase, node) ->
                qsTile?.apply {
                    state = if (phase == ConnectionState.DISCONNECTED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                    label = "Ember"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = when (phase) {
                            ConnectionState.CONNECTED -> node?.title ?: "Connected"
                            ConnectionState.CONNECTING -> "Connecting…"
                            ConnectionState.DISCONNECTED -> "Off"
                        }
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onClick() {
        when {
            VpnState.phase.value != ConnectionState.DISCONNECTED -> XrayVpnService.stop(this)
            VpnService.prepare(this) == null -> XrayVpnService.start(this)
            else -> openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
