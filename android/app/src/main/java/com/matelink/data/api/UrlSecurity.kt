package com.matelink.data.api

import java.net.InetAddress
import java.net.URI

/** Restricts bearer credentials to HTTPS or trusted local HTTP endpoints. */
object UrlSecurity {

    sealed class Validation {
        data class Valid(val normalizedUrl: String, val lanHttpWarning: Boolean) : Validation()
        data class Invalid(val message: String) : Validation()
    }

    fun normalizeAndValidate(input: String): Validation {
        val value = input.trim()
        if (value.isBlank()) return Validation.Invalid("地址格式不正确")
        val uri = try { URI(value) } catch (_: Exception) { return Validation.Invalid("地址格式不正确") }
        val scheme = uri.scheme?.lowercase() ?: return Validation.Invalid("地址格式不正确")
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || uri.port !in -1..65535) return Validation.Invalid("地址格式不正确")
        if (uri.path.orEmpty().let { it.isNotEmpty() && it != "/" }) return Validation.Invalid("只填写服务器根地址，不要追加 /api/v1 或其他接口路径。")
        val host = uri.host ?: return Validation.Invalid("地址格式不正确")
        val authority = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val normalized = buildString {
            append(scheme).append("://").append(authority)
            if (uri.port != -1) append(':').append(uri.port)
        }
        if (scheme == "http" && !isSafe(normalized)) return Validation.Invalid("当前公网 HTTP 不安全")
        return Validation.Valid(normalized, scheme == "http")
    }
    enum class Verdict { Https, LocalHttp, Unsafe }

    private val ipLiteral = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$")

    fun classify(baseUrl: String): Verdict {
        val uri = try { URI(baseUrl.trim()) } catch (_: Exception) { return Verdict.Unsafe }
        val scheme = uri.scheme?.lowercase() ?: return Verdict.Unsafe
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return Verdict.Unsafe
        if (host.isBlank()) return Verdict.Unsafe
        if (scheme == "https") return Verdict.Https
        if (scheme != "http") return Verdict.Unsafe

        if (!ipLiteral.matches(host)) {
            return if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local", ignoreCase = true)) {
                Verdict.LocalHttp
            } else {
                Verdict.Unsafe
            }
        }

        return try {
            val address = InetAddress.getByName(host)
            val numericAddress = address.hostAddress ?: return Verdict.Unsafe
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                numericAddress.lowercase().startsWith("fc") || numericAddress.lowercase().startsWith("fd")) {
                Verdict.LocalHttp
            } else {
                Verdict.Unsafe
            }
        } catch (_: Exception) {
            Verdict.Unsafe
        }
    }

    fun isSafe(baseUrl: String): Boolean = classify(baseUrl) != Verdict.Unsafe
}
