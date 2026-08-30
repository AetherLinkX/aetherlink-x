package com.palazik.vpn.compat

/**
 * Minimal re-implementation of the android.net.Uri surface the app uses, so
 * ProfileCodec and friends port from Android without semantic changes.
 *
 * Matches Android behaviour for the cases the codecs rely on:
 *  - getHost() strips IPv6 brackets
 *  - getPort() is -1 when absent
 *  - userInfo / fragment / query parameters are percent-DECODED
 *  - encode() percent-encodes everything except Android's default allow set
 *  - decode() decodes %XX but does NOT treat '+' as space
 */
class Uri private constructor(
    val scheme: String?,
    private val encodedUserInfo: String?,
    private val encodedHost: String?,
    val port: Int,
    private val encodedPath: String,
    private val encodedQuery: String?,
    private val encodedFragment: String?,
) {
    val userInfo: String? get() = encodedUserInfo?.let { decode(it) }
    val host: String? get() = encodedHost?.removePrefix("[")?.removeSuffix("]")
    val fragment: String? get() = encodedFragment?.let { decode(it) }

    val queryParameterNames: Set<String>
        get() {
            val q = encodedQuery ?: return emptySet()
            return q.split("&")
                .filter { it.isNotEmpty() }
                .map { decode(it.substringBefore("=")) }
                .toCollection(LinkedHashSet())
        }

    fun getQueryParameter(name: String): String? {
        val q = encodedQuery ?: return null
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore("="))
            if (key == name) {
                return if ("=" in pair) decode(pair.substringAfter("=")) else ""
            }
        }
        return null
    }

    override fun toString(): String = buildString {
        if (scheme != null) append(scheme).append("://")
        if (encodedUserInfo != null) append(encodedUserInfo).append('@')
        if (encodedHost != null) append(encodedHost)
        if (port > 0) append(':').append(port)
        append(encodedPath)
        if (encodedQuery != null) append('?').append(encodedQuery)
        if (encodedFragment != null) append('#').append(encodedFragment)
    }

    class Builder {
        private var scheme: String? = null
        private var encodedAuthority: String = ""
        private val queryPairs = mutableListOf<Pair<String, String>>()
        private var fragment: String? = null

        fun scheme(s: String) = apply { scheme = s }
        fun encodedAuthority(authority: String) = apply { encodedAuthority = authority }
        fun appendQueryParameter(key: String, value: String?) = apply {
            queryPairs += key to (value ?: "")
        }
        fun fragment(f: String?) = apply { fragment = f }

        fun build(): Uri = parse(buildString {
            if (scheme != null) append(scheme).append("://")
            append(encodedAuthority)
            if (queryPairs.isNotEmpty()) {
                append('?')
                append(queryPairs.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })
            }
            if (fragment != null) append('#').append(encode(fragment!!))
        })
    }

    companion object {

        fun parse(raw: String): Uri {
            var rest = raw
            var fragment: String? = null
            val hashIdx = rest.indexOf('#')
            if (hashIdx >= 0) {
                fragment = rest.substring(hashIdx + 1)
                rest = rest.substring(0, hashIdx)
            }
            var query: String? = null
            val qIdx = rest.indexOf('?')
            if (qIdx >= 0) {
                query = rest.substring(qIdx + 1)
                rest = rest.substring(0, qIdx)
            }
            var scheme: String? = null
            val schemeIdx = rest.indexOf("://")
            var authorityAndPath = rest
            if (schemeIdx >= 0) {
                scheme = rest.substring(0, schemeIdx)
                authorityAndPath = rest.substring(schemeIdx + 3)
            }
            val slashIdx = authorityAndPath.indexOf('/')
            val authority: String
            val path: String
            if (slashIdx >= 0) {
                authority = authorityAndPath.substring(0, slashIdx)
                path = authorityAndPath.substring(slashIdx)
            } else {
                authority = authorityAndPath
                path = ""
            }

            var userInfo: String? = null
            var hostPort = authority
            val atIdx = authority.lastIndexOf('@')
            if (atIdx >= 0) {
                userInfo = authority.substring(0, atIdx)
                hostPort = authority.substring(atIdx + 1)
            }

            var host: String? = null
            var port = -1
            if (hostPort.isNotEmpty()) {
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close >= 0) {
                        host = hostPort.substring(0, close + 1)
                        val after = hostPort.substring(close + 1)
                        if (after.startsWith(":")) port = after.drop(1).toIntOrNull() ?: -1
                    } else {
                        host = hostPort
                    }
                } else {
                    val colonIdx = hostPort.lastIndexOf(':')
                    if (colonIdx >= 0 && hostPort.substring(colonIdx + 1).all { it.isDigit() } &&
                        colonIdx + 1 < hostPort.length
                    ) {
                        host = hostPort.substring(0, colonIdx)
                        port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1
                    } else {
                        host = hostPort
                    }
                }
            }

            return Uri(scheme, userInfo, host, port, path, query, fragment)
        }

        /** Android's default unreserved set: [A-Za-z0-9_\-!.~'()*] */
        private const val DEFAULT_ALLOWED = "_-!.~'()*"

        fun encode(s: String, allow: String? = null): String {
            val allowed = DEFAULT_ALLOWED + (allow ?: "")
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in allowed) {
                    sb.append(c)
                } else {
                    sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
            return sb.toString()
        }

        fun decode(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        out.write(v)
                        i += 3
                        continue
                    }
                }
                for (b in c.toString().toByteArray(Charsets.UTF_8)) out.write(b.toInt())
                i++
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}
