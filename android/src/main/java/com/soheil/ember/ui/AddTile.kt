package com.soheil.ember.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import com.soheil.ember.R
import com.soheil.ember.vpn.QuickConnectTile

/**
 * Asks Android to put Ember's tile in the quick settings (the panel that opens when you pull the top of the screen
 * down). Android shows its own "Add Ember?" question, so the user decides. Returns false when this Android version
 * (older than 13) cannot ask: the tile is then added by hand from the panel's edit button.
 */
fun requestQuickSettingsTile(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return false
    manager.requestAddTileService(
        ComponentName(context, QuickConnectTile::class.java),
        "Ember",
        Icon.createWithResource(context, R.drawable.ic_tile_ember),
        context.mainExecutor,
    ) { }
    return true
}

/**
 * Asks the launcher to place Ember's widget on the home screen (it shows its own "Add?" question). Returns false when
 * the launcher cannot be asked: the widget is then added by hand from the widget list (touch and hold the home screen).
 */
fun requestHomeScreenWidget(context: Context): Boolean {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported) return false
    return manager.requestPinAppWidget(ComponentName(context, com.soheil.ember.widget.ConnectWidget::class.java), null, null)
}
