package com.palazik.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.palazik.vpn.R
import com.palazik.vpn.service.palazikVpnService
import com.palazik.vpn.ui.MainActivity

/**
 * Home-screen widget that mirrors the VPN connection state and toggles it on tap.
 *
 * Tapping while disconnected starts the service directly if VPN permission is already
 * granted; otherwise it opens MainActivity so the user can grant consent (mirrors the
 * Quick Settings tile in [com.palazik.vpn.service.VpnTileService]).
 */
class VpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) toggle(context)
        // Re-render on any received broadcast (toggle, or our own refresh).
        refresh(context)
    }

    private fun toggle(context: Context) {
        val state = palazikVpnService.connectionState.value
        val running = state == palazikVpnService.ServiceState.RUNNING ||
            state == palazikVpnService.ServiceState.STARTING

        when {
            running -> context.startService(
                Intent(context, palazikVpnService::class.java).apply { action = palazikVpnService.ACTION_STOP }
            )
            VpnService.prepare(context) == null -> context.startForegroundService(
                Intent(context, palazikVpnService::class.java).apply { action = palazikVpnService.ACTION_START }
            )
            // Consent dialog required — route through the activity.
            else -> context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.palazik.vpn.WIDGET_TOGGLE"

        /** Re-render every placed widget to reflect the current connection state. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
            ids.forEach { renderWidget(context, manager, it) }
        }

        private fun renderWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val state = palazikVpnService.connectionState.value
            val views = RemoteViews(context.packageName, R.layout.widget_vpn)

            val (statusRes, buttonRes) = when (state) {
                palazikVpnService.ServiceState.RUNNING  -> R.string.widget_connected to R.string.disconnect
                palazikVpnService.ServiceState.STARTING -> R.string.widget_connecting to R.string.disconnect
                palazikVpnService.ServiceState.STOPPING -> R.string.widget_connecting to R.string.connect
                else                                     -> R.string.widget_disconnected to R.string.connect
            }
            views.setTextViewText(R.id.widget_status, context.getString(statusRes))
            views.setTextViewText(R.id.widget_button, context.getString(buttonRes))

            val serverName = palazikVpnService.activeProfile?.name
            if (serverName.isNullOrBlank()) {
                views.setViewVisibility(R.id.widget_server, android.view.View.GONE)
            } else {
                views.setTextViewText(R.id.widget_server, serverName)
                views.setViewVisibility(R.id.widget_server, android.view.View.VISIBLE)
            }

            val toggleIntent = Intent(context, VpnWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            val pending = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            views.setOnClickPendingIntent(R.id.widget_button, pending)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
