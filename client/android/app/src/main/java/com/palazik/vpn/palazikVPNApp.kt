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
import com.palazik.vpn.data.repository.AppUpdateScheduler
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
        const val CHANNEL_UPDATES = "aetherlinkx_updates"
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
            val updates = NotificationChannel(
                CHANNEL_UPDATES,
                "Обновления AetherLink X",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Уведомления о новых версиях приложения"
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(updates)
        }
        SubscriptionUpdateScheduler.sync(this, loadAppSettings())
        AppUpdateScheduler.schedule(this)

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
