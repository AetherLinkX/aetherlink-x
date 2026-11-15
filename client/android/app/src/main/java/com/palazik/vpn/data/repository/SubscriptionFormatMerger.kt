package com.palazik.vpn.data.repository

import com.palazik.vpn.data.model.VpnProfile
import java.util.ArrayDeque

/** Combine raw/native links with JSON-only locations without dropping provider duplicates. */
internal object SubscriptionFormatMerger {
    fun merge(primary: List<VpnProfile>, json: List<VpnProfile>): List<VpnProfile> {
        val remaining = LinkedHashMap<String, ArrayDeque<VpnProfile>>()
        json.forEach { profile ->
            remaining.getOrPut(identity(profile)) { ArrayDeque() }.addLast(profile)
        }
        val merged = primary.map { profile ->
            remaining[identity(profile)]?.pollFirst() ?: profile
        }
        return merged + remaining.values.flatMap { it.toList() }
    }

    private fun identity(profile: VpnProfile) = with(profile) {
        listOf(name.trim(), protocol.name, address.trim().lowercase(), port.toString(),
            transport.name, path, host.trim().lowercase()).joinToString("\u0000")
    }
}
