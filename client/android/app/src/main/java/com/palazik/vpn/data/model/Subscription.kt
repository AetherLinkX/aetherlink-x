package com.palazik.vpn.data.model

import java.util.UUID

data class Subscription(
    val id: String   = UUID.randomUUID().toString(),
    val name: String = "Unnamed Subscription",
    val url: String  = "",
    val lastUpdated: Long = 0L,
    val profileCount: Int = 0,

    // ── Usage info, parsed from the `Subscription-Userinfo` response header ──────
    // Format: "upload=455; download=2342; total=10737418240; expire=2218532"
    // All in bytes except `expire` (Unix epoch seconds). -1 means "not reported".
    val uploadBytes: Long = -1L,
    val downloadBytes: Long = -1L,
    val totalBytes: Long = -1L,
    val expireEpochSec: Long = -1L,
) {
    /** Bytes consumed so far (upload + download), or -1 if the provider didn't report it. */
    val usedBytes: Long
        get() = if (uploadBytes < 0 && downloadBytes < 0) -1L
        else uploadBytes.coerceAtLeast(0) + downloadBytes.coerceAtLeast(0)

    val hasUsageInfo: Boolean get() = usedBytes >= 0 || totalBytes >= 0
    val hasExpiry: Boolean get() = expireEpochSec > 0
}
