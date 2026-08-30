package com.palazik.vpn.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.palazik.vpn.data.model.AppSettingsCodec
import com.palazik.vpn.di.RepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("palazik_profiles", Context.MODE_PRIVATE)
        val settings = AppSettingsCodec.fromJson(prefs.getString(AppSettingsCodec.KEY, null))
        if (!settings.autoUpdateSubscriptions) return Result.success()

        // BUG FIX: use the Hilt singleton ProfileRepository (the same instance the UI
        // observes) rather than constructing a throwaway copy whose updates the running
        // app would never see and could later clobber.
        val repo = EntryPointAccessors
            .fromApplication(applicationContext, RepositoryEntryPoint::class.java)
            .profileRepository()

        if (repo.subscriptions.value.isEmpty()) return Result.success()

        val results = repo.updateAllSubscriptions()
        return if (results.any { it.isSuccess }) Result.success() else Result.retry()
    }
}
