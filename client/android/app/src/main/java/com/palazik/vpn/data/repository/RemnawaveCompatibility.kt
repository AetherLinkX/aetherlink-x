package com.palazik.vpn.data.repository

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stable, version-neutral bridge between Remnawave subscriptions and native ALX.
 *
 * Remnawave changed its API representation of custom response headers between major
 * versions, but the HTTP response received by a client remains the same. Providers can
 * therefore publish an ALX share link in either X-AetherLink-Profile or X-ALX-Profile.
 */
internal object RemnawaveCompatibility {
    private val profileHeaderNames = setOf("x-aetherlink-profile", "x-alx-profile")

    fun nativeProfileLinks(headers: Map<String, List<String>>): List<String> =
        headers.asSequence()
            .filter { (name, _) -> name.lowercase() in profileHeaderNames }
            .flatMap { (_, values) -> values.asSequence() }
            .flatMap { value -> decodeHeaderValue(value).lineSequence() }
            .map(String::trim)
            .filter { it.startsWith("aetherlink://", ignoreCase = true) }
            .distinct()
            .toList()

    private fun decodeHeaderValue(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith("base64:", ignoreCase = true)) return value
        val payload = value.substringAfter(':').trim()
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
        return decoders.firstNotNullOfOrNull { decoder ->
            runCatching {
                String(decoder.decode(padBase64(payload)), StandardCharsets.UTF_8)
            }.getOrNull()
        }.orEmpty()
    }

    private fun padBase64(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)
}
