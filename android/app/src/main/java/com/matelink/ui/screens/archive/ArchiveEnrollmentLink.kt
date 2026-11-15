package com.matelink.ui.screens.archive

/** Intentionally canonical: no URI normalization, decoded aliases, OAuth tickets or extra parameters. */
object ArchiveEnrollmentLink {
    private val exact = Regex(Regex.escape("https://auth.teslalink.joviluma.com/archive/enroll?handle=") + "([0-9a-f]{64})")
    fun parse(raw: String?): String? = raw?.let { exact.matchEntire(it)?.groupValues?.get(1) }
}
