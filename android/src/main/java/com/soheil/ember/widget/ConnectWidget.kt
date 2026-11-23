package com.soheil.ember.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.RemoteViews
import com.soheil.ember.EmberVpnApp
import com.soheil.ember.MainActivity
import com.soheil.ember.R
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.VpnState
import com.soheil.ember.vpn.XrayVpnService

/** A one-tap VPN widget that switches between compact and expanded layouts as it is resized. */
class ConnectWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        refresh(context, intArrayOf(appWidgetId))
    }

    companion object {
        /** Redraws placed widgets when VPN state, connection detail, language, size, or the selected server changes. */
        fun refresh(context: Context) = refresh(context, null)

        private fun refresh(context: Context, requestedIds: IntArray?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = requestedIds ?: manager.getAppWidgetIds(ComponentName(context, ConnectWidget::class.java))
            if (ids.isEmpty()) return

            val engine = (context.applicationContext as EmberVpnApp).engine
            val words = engine.strings
            val phase = VpnState.phase.value
            val connected = phase == ConnectionState.CONNECTED
            val status = when (phase) {
                ConnectionState.CONNECTED -> words.connected
                ConnectionState.CONNECTING -> VpnState.progress.value?.takeIf(String::isNotBlank) ?: words.connecting
                ConnectionState.DISCONNECTED -> VpnState.error.value?.takeIf(String::isNotBlank) ?: words.disconnected
            }
            val server = (VpnState.node.value ?: engine.selectedNode())?.title.orEmpty()
            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val tap = when {
                phase != ConnectionState.DISCONNECTED -> PendingIntent.getService(
                    context,
                    11,
                    Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_DISCONNECT),
                    immutable,
                )
                engine.selectedNode() != null && VpnService.prepare(context) == null -> PendingIntent.getForegroundService(
                    context,
                    12,
                    Intent(context, XrayVpnService::class.java),
                    immutable,
                )
                else -> PendingIntent.getActivity(context, 13, Intent(context, MainActivity::class.java), immutable)
            }

            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val compact = width < EXPANDED_MIN_WIDTH_DP || height < EXPANDED_MIN_HEIGHT_DP
                val layout = if (compact) R.layout.widget_connect_compact else R.layout.widget_connect
                val views = RemoteViews(context.packageName, layout)
                views.setTextViewText(R.id.widget_status, status)
                views.setTextViewText(R.id.widget_server, server)
                // The orb glows while connecting or connected and dims when off.
                views.setInt(R.id.widget_orb, "setImageAlpha", if (phase == ConnectionState.DISCONNECTED) 70 else 255)
                views.setInt(R.id.widget_power, "setImageAlpha", if (connected) 255 else 140)
                views.setOnClickPendingIntent(R.id.widget_root, tap)
                manager.updateAppWidget(id, views)
            }
        }

        private const val EXPANDED_MIN_WIDTH_DP = 160
        private const val EXPANDED_MIN_HEIGHT_DP = 130
    }
}
