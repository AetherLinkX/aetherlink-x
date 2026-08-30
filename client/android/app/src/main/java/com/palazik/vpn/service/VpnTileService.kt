package com.palazik.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.palazik.vpn.ui.MainActivity

/**
 * Quick Settings tile to toggle the VPN from the notification shade.
 *
 * Tapping while disconnected: if VPN permission is already granted we start the
 * service directly; otherwise we open MainActivity so the user can grant it.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val state = palazikVpnService.connectionState.value
        val running = state == palazikVpnService.ServiceState.RUNNING ||
            state == palazikVpnService.ServiceState.STARTING

        if (running) {
            startService(Intent(this, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_STOP
            })
        } else if (VpnService.prepare(this) == null) {
            // Permission already granted — start straight away
            startForegroundService(Intent(this, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_START
            })
        } else {
            // Need the consent dialog — route through the activity
            val activityIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, activityIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(activityIntent)
            }
        }
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val running = palazikVpnService.connectionState.value == palazikVpnService.ServiceState.RUNNING
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "AetherLink X"
        tile.updateTile()
    }
}
