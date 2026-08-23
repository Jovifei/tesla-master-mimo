package com.matelink.ui.common

import java.net.URI

object PublicInfoLinks {
    enum class Page(val suffix: String) {
        HELP("help/"),
        TERMS("terms/"),
        PRIVACY("privacy/"),
        LEGAL("legal/"),
        CHANGELOG("changelog/")
    }

    fun url(baseUrl: String, page: Page): String? {
        val uri = try { URI(baseUrl.trim()) } catch (_: Exception) { return null }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.port > 65535 || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || uri.path.orEmpty().let { it.isNotEmpty() && it != "/" }) return null
        return "https://${uri.authority}/${page.suffix}"
    }
}
