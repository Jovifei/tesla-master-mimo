package com.matelink.data.api

import java.net.InetAddress
import java.net.URI

/**
 * 校验 baseUrl 是否可安全发送 Bearer token。
 *
 * 策略：
 * - HTTPS → 始终安全
 * - HTTP + 私有/回环/link-local IP 字面量 或 localhost/`.local` 主机名 → 安全（自托管 LAN 用例）
 * - HTTP + 公网 IP 或公网域名 → 不安全（token 会明文泄露）
 * - 空/格式错误 → 不安全
 *
 * 注意：仅对 IP 字面量做 InetAddress 解析（不触发公网 DNS）。
 */
object UrlSecurity {

    private val IP_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$")

    fun isSafe(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return false
        val uri = try { URI(trimmed) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        if (host.isEmpty()) return false

        if (scheme == "https") return true
        if (scheme != "http") return false

        // Hostname: only localhost / .local allowed
        if (!isIpLiteral(host)) {
            return host == "localhost" || host.endsWith(".local")
        }

        // IP literal: resolve without public DNS, check private/loopback/link-local
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun isIpLiteral(host: String): Boolean = IP_LITERAL.matches(host)
}
