package com.palazik.vpn.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop replacement for the WorkManager-based SubscriptionUpdateScheduler:
 * a coroutine ticker that refreshes all subscriptions on the configured interval
 * while the app is running.
 */
object SubscriptionAutoUpdater {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun sync(settings: com.palazik.vpn.data.model.AppSettings) {
        job?.cancel()
        job = null
        if (!settings.autoUpdateSubscriptions) return
        val intervalMs = settings.subscriptionUpdateIntervalHours.coerceAtLeast(2L) * 3_600_000L
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { ProfileRepository.updateAllSubscriptions() }
            }
        }
    }
}
