package com.soheil.ember.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.soheil.ember.EmberVpnApp
import com.soheil.ember.MainActivity
import com.soheil.ember.R
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.VpnState
import com.soheil.ember.vpn.XrayVpnService

/**
 * A 2x2 home-screen widget: "Connected" or "Disconnected" and the server on a black card, with the Ember orb at the bottom. One tap connects or
 * disconnects; if Android's VPN permission or a chosen server is missing, the tap opens Ember instead.
 */
class ConnectWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    companion object {
        /** Redraws every placed widget from the current state (called whenever the VPN state or language changes). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ConnectWidget::class.java))
            if (ids.isEmpty()) return
            val engine = (context.applicationContext as EmberVpnApp).engine
            val words = engine.strings
            val phase = VpnState.phase.value
            val connected = phase == ConnectionState.CONNECTED
            val views = RemoteViews(context.packageName, R.layout.widget_connect)
            views.setTextViewText(
                R.id.widget_status,
                when (phase) {
                    ConnectionState.CONNECTED -> words.connected
                    ConnectionState.CONNECTING -> words.connecting
                    ConnectionState.DISCONNECTED -> words.disconnected
                },
            )
            views.setTextViewText(R.id.widget_server, (VpnState.node.value ?: engine.selectedNode())?.title.orEmpty())
            // the orb glows while connecting or connected and dims when off
            views.setInt(R.id.widget_orb, "setImageAlpha", if (phase == ConnectionState.DISCONNECTED) 70 else 255)
            views.setInt(R.id.widget_power, "setImageAlpha", if (connected) 255 else 140)

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val tap = when {
                phase != ConnectionState.DISCONNECTED ->
                    PendingIntent.getService(context, 11, Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_DISCONNECT), immutable)
                engine.selectedNode() != null && VpnService.prepare(context) == null ->
                    PendingIntent.getForegroundService(context, 12, Intent(context, XrayVpnService::class.java), immutable)
                else -> PendingIntent.getActivity(context, 13, Intent(context, MainActivity::class.java), immutable)
            }
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            manager.updateAppWidget(ids, views)
        }
    }
}
