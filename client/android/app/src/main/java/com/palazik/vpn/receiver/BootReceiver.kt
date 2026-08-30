package com.palazik.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.service.palazikVpnService
import org.json.JSONArray
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "palazikVPN.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)
        val settings = runCatching {
            JSONObject(prefs.getString("app_settings", null) ?: "{}")
                .optBoolean("startOnBoot", false)
        }.getOrDefault(false)
        if (!settings || VpnService.prepare(context) != null) return

        val activeProfile = runCatching {
            val links = JSONArray(prefs.getString("profiles_links", "[]"))
            val meta = JSONArray(prefs.getString("profiles_meta", "[]"))
            val activeIds = mutableSetOf<String>()
            for (i in 0 until meta.length()) {
                val o = meta.getJSONObject(i)
                if (o.optBoolean("isActive", false)) activeIds += o.getString("id")
            }
            for (i in 0 until links.length()) {
                val profile = ProfileCodec.decode(links.getString(i))
                if (profile != null && profile.id in activeIds) return@runCatching profile
            }
            null
        }.getOrNull() ?: return

        palazikVpnService.activeProfile = activeProfile
        val serviceIntent = Intent(context, palazikVpnService::class.java).apply {
            action = palazikVpnService.ACTION_START
            putExtra(palazikVpnService.EXTRA_PROFILE, activeProfile.id)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { e ->
            Log.w(TAG, "Auto-connect on boot failed: ${e.message}", e)
        }
    }
}
