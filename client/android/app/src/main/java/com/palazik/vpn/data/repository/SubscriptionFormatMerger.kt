package com.palazik.vpn.data.repository

import com.palazik.vpn.data.model.VpnProfile
import java.util.ArrayDeque

/** Combine raw/native links with JSON-only locations without dropping provider duplicates. */
internal object SubscriptionFormatMerger {
    fun merge(primary: List<VpnProfile>, json: List<VpnProfile>): List<VpnProfile> {
        if (json.isEmpty()) return primary
        val occurrences = LinkedHashMap<String, ArrayDeque<Int>>()
        primary.forEachIndexed { index, profile ->
            occurrences.getOrPut(identity(profile)) { ArrayDeque() }.addLast(index)
        }
        val matched = IntArray(primary.size) { -1 }
        json.forEachIndexed { index, profile ->
            occurrences[identity(profile)]?.pollFirst()?.let { matched[it] = index }
        }
        // JSON is the ordered provider list, including JSON-only locations.
        // Reinsert raw-only entries (e.g. native ALX) before their next shared
        // neighbour instead of appending every new JSON location at the bottom.
        val before = Array(json.size + 1) { mutableListOf<VpnProfile>() }
        var nextShared = json.size
        for (index in primary.indices.reversed()) {
            if (matched[index] >= 0) nextShared = matched[index]
            else before[nextShared].add(primary[index])
        }
        return buildList {
            json.forEachIndexed { index, profile ->
                addAll(before[index].asReversed())
                add(profile)
            }
            addAll(before[json.size].asReversed())
        }
    }

    private fun identity(profile: VpnProfile) = with(profile) {
        listOf(name.trim(), protocol.name, address.trim().lowercase(), port.toString(),
            transport.name, path, host.trim().lowercase()).joinToString("\u0000")
    }
}
