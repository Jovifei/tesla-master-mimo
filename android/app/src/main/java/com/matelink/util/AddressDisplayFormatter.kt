package com.matelink.util

private val HAN_CHARACTER = Regex("[\u4E00-\u9FFF]")

private val ADDRESS_TRANSLATIONS = listOf(
    // Brands & POIs
    "Tesla Supercharger" to "特斯拉超级充电站",
    "Tesla Service Center" to "特斯拉服务中心",
    "Supercharger" to "超级充电站",
    "Destination Charger" to "目的地充电站",
    "Destination Charging" to "目的地充电站",
    "FamilyMart" to "全家便利店",
    "Starbucks" to "星巴克",
    "McDonald's" to "麦当劳",
    "McDonalds" to "麦当劳",
    "KFC" to "肯德基",
    "7-Eleven" to "7-Eleven",
    "Lawson" to "罗森",

    // Specific POI complexes
    "Software Park" to "软件园",
    "Technology Park" to "科技园",
    "Industrial Park" to "工业园",
    "High-tech Zone" to "高新区",
    "Railway Station" to "火车站",
    "Airport" to "机场",

    // Districts (Hangzhou & surrounding)
    "Yuhang District" to "余杭区",
    "Yuhang" to "余杭区",
    "Xihu District" to "西湖区",
    "Xihu" to "西湖区",
    "Gongshu District" to "拱墅区",
    "Gongshu" to "拱墅区",
    "Binjiang District" to "滨江区",
    "Binjiang" to "滨江区",
    "Xiaoshan District" to "萧山区",
    "Xiaoshan" to "萧山区",
    "Lin'an District" to "临安区",
    "Linan District" to "临安区",
    "Lin'an" to "临安区",
    "Fuyang District" to "富阳区",
    "Fuyang" to "富阳区",
    "Shangcheng District" to "上城区",
    "Shangcheng" to "上城区",
    "Xiacheng District" to "下城区",
    "Xiacheng" to "下城区",
    "Jianggan District" to "江干区",
    "Jianggan" to "江干区",
    "Qiantang District" to "钱塘区",
    "Qiantang" to "钱塘区",

    // Cities & Provinces
    "Hangzhou" to "杭州市",
    "Ningbo" to "宁波市",
    "Shaoxing" to "绍兴市",
    "Jiaxing" to "嘉兴市",
    "Huzhou" to "湖州市",
    "Jinhua" to "金华市",
    "Wenzhou" to "温州市",
    "Taizhou" to "台州市",
    "Zhoushan" to "舟山市",
    "Quzhou" to "衢州市",
    "Lishui" to "丽水市",
    "Shanghai" to "上海市",
    "Suzhou" to "苏州市",
    "Zhejiang" to "浙江省",
    "Jiangsu" to "江苏省",
    "China" to "中国"
)

/**
 * TeslaMate geocoding can return a Chinese street followed by an English
 * administrative suffix, or a purely English OSM Nominatim address.
 * Prefer Chinese components, and translate recognizable English POIs/districts.
 */
fun String?.toChineseDisplayAddress(): String? {
    val original = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = original
        .split(',', '\uFF0C')
        .map(String::trim)
        .filter(String::isNotEmpty)

    val chineseParts = parts.filter { HAN_CHARACTER.containsMatchIn(it) }
    if (chineseParts.isNotEmpty()) {
        return chineseParts.joinToString(" ")
    }

    // Fully non-Chinese address: translate known English POI/city/district terms
    val translatedParts = parts.map { part ->
        var translated = part
        for ((en, zh) in ADDRESS_TRANSLATIONS) {
            translated = translated.replace(Regex("(?i)\\b" + Regex.escape(en) + "\\b"), zh)
        }
        translated.trim()
    }.filter { it.isNotEmpty() && it != "中国" }

    val hasChinese = translatedParts.any { HAN_CHARACTER.containsMatchIn(it) }
    return if (hasChinese) {
        translatedParts.joinToString(" ").takeIf { it.isNotEmpty() } ?: original
    } else {
        original
    }
}
