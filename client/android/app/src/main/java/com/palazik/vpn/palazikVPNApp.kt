package com.palazik.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.AppSettingsCodec
import com.palazik.vpn.data.SecurePreferences
import com.palazik.vpn.data.repository.SubscriptionUpdateScheduler
import com.palazik.vpn.service.palazikVpnService
import com.palazik.vpn.widget.VpnWidgetProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class palazikVPNApp : Application() {

    companion object {
        const val CHANNEL_VPN = "palazikvpn_service"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_VPN,
                "AetherLink X Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        SubscriptionUpdateScheduler.sync(this, loadAppSettings())

        // Keep the home-screen widget in sync with the live connection state.
        appScope.launch {
            palazikVpnService.connectionState.collect { VpnWidgetProvider.refresh(this@palazikVPNApp) }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun loadAppSettings(): AppSettings {
        val prefs = SecurePreferences.get(this)
        return AppSettingsCodec.fromJson(prefs.getString(AppSettingsCodec.KEY, null))
    }
}
