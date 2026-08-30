package com.palazik.vpn.compat

/**
 * android.util.Base64-compatible facade over java.util.Base64 so the codecs
 * port unchanged. Only the flag combinations the app actually uses are handled.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    fun decode(input: String, flags: Int): ByteArray {
        // Android's decoder is lenient about whitespace and missing padding.
        val cleaned = input.filterNot { it.isWhitespace() }
        val padded = cleaned.padEnd(cleaned.length + (4 - cleaned.length % 4) % 4, '=')
        val decoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlDecoder()
        } else {
            java.util.Base64.getDecoder()
        }
        return decoder.decode(padded)
    }

    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        // java.util.Base64 never wraps, which matches every NO_WRAP use in the app.
        return encoder.encodeToString(input)
    }
}
