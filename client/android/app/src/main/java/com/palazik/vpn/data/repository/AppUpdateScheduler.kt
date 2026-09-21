package com.palazik.vpn.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AppUpdateScheduler {
    private const val PERIODIC_WORK = "aetherlinkx_app_update_periodic"
    private const val INITIAL_WORK = "aetherlinkx_app_update_initial"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val manager = WorkManager.getInstance(context.applicationContext)

        manager.enqueueUniqueWork(
            INITIAL_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(constraints)
                .build(),
        )
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
    }
}
