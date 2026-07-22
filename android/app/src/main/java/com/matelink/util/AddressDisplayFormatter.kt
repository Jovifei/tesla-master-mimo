package com.matelink.util

private val HAN_CHARACTER = Regex("[\\u4E00-\\u9FFF]")

/**
 * TeslaMate geocoding can return a Chinese street followed by an English
 * administrative suffix. Prefer the Chinese components without damaging fully
 * non-Chinese addresses.
 */
fun String?.toChineseDisplayAddress(): String? {
    val original = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val chineseParts = original
        .split(',', '\uFF0C')
        .map(String::trim)
        .filter { HAN_CHARACTER.containsMatchIn(it) }

    return chineseParts.joinToString(" ").takeIf { it.isNotEmpty() } ?: original
}
