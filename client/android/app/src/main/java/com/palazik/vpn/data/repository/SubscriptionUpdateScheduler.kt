package com.palazik.vpn.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.palazik.vpn.data.model.AppSettings
import java.util.concurrent.TimeUnit

object SubscriptionUpdateScheduler {
    private const val WORK_NAME = "subscription_auto_update"

    fun sync(context: Context, settings: AppSettings) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        if (!settings.autoUpdateSubscriptions) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val interval = settings.subscriptionUpdateIntervalHours.coerceAtLeast(2L)
        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(interval, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
