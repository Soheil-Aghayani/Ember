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
 * A 2x2 home-screen widget: the Ember orb, "Connected" or "Disconnected", and the server. One tap connects or
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
            views.setInt(R.id.widget_root, "setBackgroundResource", if (connected) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
            views.setInt(R.id.widget_orb, "setImageAlpha", if (phase == ConnectionState.DISCONNECTED) 130 else 255)

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
