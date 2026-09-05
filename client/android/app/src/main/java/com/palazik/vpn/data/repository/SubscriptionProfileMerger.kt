package com.palazik.vpn.data.repository

import com.palazik.vpn.data.model.VpnProfile
import java.util.ArrayDeque

/** Reconciles a downloaded subscription without retaining stale network credentials. */
internal object SubscriptionProfileMerger {
    fun merge(fresh: List<VpnProfile>, previous: List<VpnProfile>): List<VpnProfile> {
        val previousByIdentity = LinkedHashMap<String, ArrayDeque<VpnProfile>>()
        previous.forEach { profile ->
            previousByIdentity.getOrPut(profile.locationIdentity()) { ArrayDeque() }.addLast(profile)
        }

        return fresh.map { downloaded ->
            val stored = previousByIdentity[downloaded.locationIdentity()]?.pollFirst()
            downloaded.copy(
                id = stored?.id ?: downloaded.id,
                isActive = stored?.isActive == true,
                addedAt = stored?.addedAt ?: downloaded.addedAt,
                latencyMs = stored?.latencyMs ?: -1L,
                lastTested = stored?.lastTested ?: 0L,
            )
        }
    }

    /** Stable provider-visible identity; excludes credentials that can rotate in place. */
    private fun VpnProfile.locationIdentity(): String = listOf(
        name.trim(),
        protocol.name,
        address.trim().lowercase(),
        port.toString(),
        transport.name,
        path,
        host.trim().lowercase(),
    ).joinToString("\u0000")
}
