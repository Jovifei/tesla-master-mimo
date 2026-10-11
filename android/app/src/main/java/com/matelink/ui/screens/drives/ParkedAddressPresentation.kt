package com.matelink.ui.screens.drives

private val parkedHan = Regex("[\\p{IsHan}]")
private val parkedAddressSeparators = Regex("[,，;；\\n|｜()（）]")
private val englishPrefix = Regex("^[A-Za-z][A-Za-z .'-]*\\s+(?=[\\p{IsHan}])")
private val englishSuffix = Regex("\\s+[A-Za-z][A-Za-z .'-]*$")

/** Display only: prefer an already-Chinese candidate, never translate or mutate source addresses. */
internal fun parkedAddressLabel(vararg candidates: String?): String? {
    val available = candidates.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    for (candidate in available) {
        val chinese = candidate.split(parkedAddressSeparators)
            .map(String::trim)
            .filter { parkedHan.containsMatchIn(it) }
            .map { it.replace(englishPrefix, "").replace(englishSuffix, "").trim() }
            .distinct()
        if (chinese.isNotEmpty()) return chinese.joinToString(" ")
    }
    // A real non-Chinese address is more useful than a blank or a made-up translation.
    return available.firstOrNull()
}
