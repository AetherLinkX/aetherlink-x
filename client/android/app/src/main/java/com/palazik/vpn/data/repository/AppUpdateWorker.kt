package com.palazik.vpn.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.palazik.vpn.R
import com.palazik.vpn.data.SecurePreferences
import com.palazik.vpn.di.RepositoryEntryPoint
import com.palazik.vpn.palazikVPNApp
import dagger.hilt.android.EntryPointAccessors

/** Periodically informs the user when a newer public Android build is available. */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val currentVersion = runCatching {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .versionName
        }.getOrNull().orEmpty()
        if (currentVersion.isBlank()) return Result.success()

        val repo = EntryPointAccessors
            .fromApplication(applicationContext, RepositoryEntryPoint::class.java)
            .profileRepository()
        val result = repo.checkForUpdate(currentVersion)
        val update = result.getOrNull() ?: return if (result.isFailure) Result.retry() else Result.success()

        val prefs = SecurePreferences.get(applicationContext)
        if (prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) == update.version) {
            return Result.success()
        }
        // The first worker can run before MainActivity has requested Android 13's
        // notification permission. Retry later instead of silently consuming the alert.
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return Result.retry()
        }

        val openRelease = PendingIntent.getActivity(
            applicationContext,
            UPDATE_NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(update.url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, palazikVPNApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("Доступно обновление AetherLink X")
            .setContentText("Новая версия ${update.version} готова к скачиванию")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Доступна новая версия AetherLink X ${update.version}. Нажмите, чтобы скачать обновление с GitHub."),
            )
            .setContentIntent(openRelease)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(UPDATE_NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, update.version).apply()
        return Result.success()
    }

    private companion object {
        const val UPDATE_NOTIFICATION_ID = 1002
        const val KEY_LAST_NOTIFIED_VERSION = "last_notified_app_version"
    }
}
