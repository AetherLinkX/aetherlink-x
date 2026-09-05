package com.palazik.vpn.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted storage for proxy credentials and subscription URLs. */
object SecurePreferences {
    private const val TAG = "AetherLinkX.Storage"
    private const val LEGACY_NAME = "palazik_profiles"
    private const val SECURE_NAME = "aetherlinkx_secure_profiles"

    @Volatile private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: create(context.applicationContext).also { cached = it }
    }

    private fun create(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                SECURE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacy(context, encrypted)
            encrypted
        } catch (e: Exception) {
            // Preserve availability on vendor devices with a broken KeyStore, but expose
            // the security downgrade in logcat instead of silently losing all profiles.
            Log.e(TAG, "Encrypted preferences unavailable; using private app storage", e)
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacy(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty() || target.getBoolean("_alx_migrated", false)) return
        val editor = target.edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.putBoolean("_alx_migrated", true)
        if (editor.commit()) legacy.edit().clear().apply()
    }
}
