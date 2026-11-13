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

private val PROVINCE_REGEX = Regex(
    "^(?:中国|中华人民共和国)?\\s*" +
    "(北京市|上海市|天津市|重庆市|北京|上海|天津|重庆|" +
    "河北省|山西省|辽宁省|吉林省|黑龙江省|江苏省|浙江省|安徽省|福建省|江西省|山东省|河南省|湖北省|湖南省|广东省|海南省|四川省|贵州省|云南省|陕西省|甘肃省|青海省|台湾省|" +
    "内蒙古自治区|内蒙古|广西壮族自治区|广西|西藏自治区|西藏|宁夏回族自治区|宁夏|新疆维吾尔自治区|新疆|" +
    "香港特别行政区|香港|澳门特别行政区|澳门)\\s*"
)

private val MUNICIPALITIES = setOf("北京", "北京市", "上海", "上海市", "天津", "天津市", "重庆", "重庆市")

private val CITY_REGEX = Regex("^([^省市区县旗街道镇乡\\s]+?(?:市|自治州|地区|盟))\\s*")
private val DISTRICT_REGEX = Regex("^([^省市区县旗街道镇乡\\s]+?(?:区|县|县级市|旗))\\s*")
private val STREET_REGEX = Regex("^([^省市区县旗街道镇乡\\s]+?(?:街道办事处|街道|镇|乡))\\s*")

private val PLACE_NAME = Regex("""(?:小区|社区|公寓|花园|家园|山庄|广场|大厦|大楼|中心|园区|科技园|软件园|产业园|商业城|商场|天街|酒店|超市|咖啡|店|停车场|充电站|医院|学校|大学|幼儿园|公园|景区|火车站|地铁站|车站|机场|乐园|车库|公馆|苑|府|园)(?=(?:\s*(?:[0-9一二三四五六七八九十]+(?:期|幢|栋|座|号楼|号门|单元)|[东西南北]门))*$)""")
private val STREET_NAME = Regex("""^.+?(?:大道|公路|大街|街|路|巷|弄)(?=\s|[0-9]|$)""")
private val STREET_BEFORE_PLACE = Regex("""^.+?(?:大道|公路|大街|街|路|巷|弄)(?:\s*[0-9]+\s*号)?\s*""")
private val HOUSE_NUMBER = Regex("""^.+?(?:大道|公路|大街|街|路|巷|弄)\s*[0-9]+\s*(?:号|弄)(?:院)?""")
private val APPROXIMATE_PLACE = Regex("""(?:附近|旁边|对面|周边|路口|交叉口)$""")
private val PLACE_PLACEHOLDER = Regex("""^(?:未知|未知位置|无|未记录|不详|中国|unknown|n/?a|[-—]+)$""", RegexOption.IGNORE_CASE)
private val PLACE_COORDINATE = Regex("""^[-+]?[0-9.]+\s*°?[NSEW]?$""", RegexOption.IGNORE_CASE)

private fun specificAddressParts(address: String?): List<Pair<String, Boolean>> =
    address.orEmpty().split(',', '，', '、').mapNotNull { raw ->
        var rest = raw.trim()
        val original = rest
        PROVINCE_REGEX.find(rest)?.takeIf { match ->
            match.groupValues[1].last() in "省市区" || match.value.trim() == rest
        }?.let { rest = rest.drop(it.value.length).trimStart() }
        CITY_REGEX.find(rest)?.takeUnless { it.value.trimEnd().endsWith("超市") }?.let {
            rest = rest.drop(it.value.length).trimStart()
        }
        DISTRICT_REGEX.find(rest)?.takeUnless { match ->
            listOf("小区", "园区", "景区", "校区", "社区", "街区").any(match.value.trimEnd()::endsWith)
        }?.let { rest = rest.drop(it.value.length).trimStart() }
        STREET_REGEX.find(rest)?.let { rest = rest.drop(it.value.length).trimStart() }
        rest.takeUnless {
            it.isBlank() || PLACE_PLACEHOLDER.matches(it) || PLACE_COORDINATE.matches(it) ||
                APPROXIMATE_PLACE.containsMatchIn(it)
        }?.let { it to (it != original) }
    }

private fun namedPlace(part: String): Boolean {
    val base = part.substringBefore('(').substringBefore('（').trimEnd()
    val suffix = PLACE_NAME.find(base)
    return suffix != null && suffix.range.first > 0 && HAN_CHARACTER.containsMatchIn(base.take(suffix.range.first))
}

/** Use a named point or numbered address; an administrative area or bare road is not a point. */
internal fun specificPlaceLabel(address: String?): String? {
    val parts = specificAddressParts(address)
    for ((part, hadAdmin) in parts) {
        val translated = part.toChineseDisplayAddress() ?: continue
        val name = if (hadAdmin || HOUSE_NUMBER.containsMatchIn(translated)) {
            val afterRoad = STREET_BEFORE_PLACE.replaceFirst(translated, "")
            afterRoad.takeIf(::namedPlace) ?: translated
        } else translated
        if (namedPlace(name)) return name
    }
    return parts.firstNotNullOfOrNull { (part, _) -> HOUSE_NUMBER.find(part)?.value }
}

internal fun recordedRoadLabel(address: String?): String? =
    specificAddressParts(address).firstNotNullOfOrNull { (part, _) ->
        STREET_NAME.find(part)?.value?.takeIf { it == part }
    }

internal fun tripEndpointLabel(address: String?, missing: String, approximateRoad: (String) -> String): String =
    specificPlaceLabel(address) ?: recordedRoadLabel(address)?.let(approximateRoad) ?: missing

internal fun preferSpecificEndpointAddress(existing: String?, candidate: String?): String? =
    if (specificPlaceLabel(candidate)?.let { candidateLabel ->
            val existingLabel = specificPlaceLabel(existing)
            existingLabel == null || (!namedPlace(existingLabel) && namedPlace(candidateLabel))
        } == true) candidate
    else existing?.takeIf(String::isNotBlank) ?: candidate?.takeIf(String::isNotBlank)

data class ParsedChineseAddress(
    val original: String,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val street: String? = null,
    val detail: String = ""
) {
    val detailOnly: String
        get() = detail.trim().takeIf { it.isNotEmpty() }
            ?: street?.trim().takeIf { !it.isNullOrEmpty() }
            ?: original.trim()

    val streetAndBeyond: String
        get() = when {
            street != null && detail.isNotBlank() -> {
                if (original.contains(" ") && !detail.startsWith(" ")) "$street $detail" else "$street$detail"
            }
            street != null -> street
            else -> detail.trim().takeIf { it.isNotEmpty() } ?: original
        }.trim()

    val districtAndBeyond: String
        get() = when {
            district != null && streetAndBeyond.isNotBlank() -> {
                if (original.contains(" ") && !streetAndBeyond.startsWith(" ")) "$district $streetAndBeyond" else "$district$streetAndBeyond"
            }
            district != null -> district
            else -> streetAndBeyond
        }.trim()

    val cityAndBeyond: String
        get() = when {
            city != null && districtAndBeyond.isNotBlank() -> {
                if (original.contains(" ") && !districtAndBeyond.startsWith(" ")) "$city $districtAndBeyond" else "$city$districtAndBeyond"
            }
            city != null -> city
            else -> districtAndBeyond
        }.trim()
}

fun simplifyAddressLandmark(raw: String): String {
    if (raw.isBlank()) return raw
    var text = raw.trim()

    // 1. Check if bracket content contains a major landmark (e.g. 鞠婆婆绵绵冰(美塘广场杭州总店) -> 美塘广场)
    val bracketMatch = Regex("[\\(（]([^\\(\\)（）]+)[\\)）]").find(text)
    if (bracketMatch != null) {
        val bracketContent = bracketMatch.groupValues[1]
        val landmarkMatch = Regex("([\\u4e00-\\u9fa5]{2,6}?(?:广场|中心|大厦|大楼|园区|科技园|软件园|商城|商业中心|商场|印象城|万达|万象城|银泰|百联|天街))").find(bracketContent)
        if (landmarkMatch != null) {
            return landmarkMatch.groupValues[1]
        }
    }

    // 2. Strip bracket branch suffixes like (xx店), (xx分店), (xx总店)
    text = text.replace(Regex("[\\(（][^\\(\\)（）]*(?:店|分店|总店|支店|分公司)[^\\(\\)（）]*[\\)）]"), "").trim()

    // 3. If text contains complex separator like "、" or "，" or ",", check if one part is a major commercial landmark
    // e.g. "鞠婆婆绵绵冰、美塘广场" -> "美塘广场"
    val subParts = text.split("、", ",", "，").map { it.trim() }.filter { it.isNotEmpty() }
    if (subParts.size > 1) {
        val majorLandmark = subParts.firstOrNull { part ->
            Regex("(?:广场|中心|大厦|大楼|园区|科技园|软件园|商城|商业中心|商场|印象城|万达|万象城|银泰|百联|天街|枢纽|站)").containsMatchIn(part)
        }
        if (majorLandmark != null) {
            text = majorLandmark
        }
    }

    return text.replace(Regex("[\\(（][^\\(\\)（）]*[\\)）]"), "").trim().ifBlank { raw.trim() }
}

fun parseChineseAddress(raw: String): ParsedChineseAddress {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ParsedChineseAddress(original = raw)

    var current = trimmed
    val provMatch = PROVINCE_REGEX.find(current)
    var province: String? = null
    var city: String? = null
    var district: String? = null
    var street: String? = null

    if (provMatch != null) {
        val matchedProv = provMatch.groupValues[1]
        province = matchedProv
        current = current.substring(provMatch.range.last + 1).trimStart()
        if (MUNICIPALITIES.contains(matchedProv)) {
            city = matchedProv
        }
    }

    if (city == null) {
        val cityMatch = CITY_REGEX.find(current)
        if (cityMatch != null) {
            city = cityMatch.groupValues[1]
            current = current.substring(cityMatch.range.last + 1).trimStart()
        }
    }

    val distMatch = DISTRICT_REGEX.find(current)
    if (distMatch != null) {
        district = distMatch.groupValues[1]
        current = current.substring(distMatch.range.last + 1).trimStart()
    }

    val streetMatch = STREET_REGEX.find(current)
    if (streetMatch != null) {
        street = streetMatch.groupValues[1]
        current = current.substring(streetMatch.range.last + 1).trimStart()
    }

    val simplifiedDetail = simplifyAddressLandmark(current)

    return ParsedChineseAddress(
        original = raw,
        province = province,
        city = city,
        district = district,
        street = street,
        detail = simplifiedDetail
    )
}

private fun normalizeAdminName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return name.trim()
        .replace(Regex("(省|市|自治区|特别行政区|自治州|地区|盟|区|县|县级市|旗|街道办事处|街道|镇|乡)$"), "")
        .trim()
}

/**
 * Formats a route start and end address pair for display.
 * - If start and end are in the same province, city, district, and street, hides common prefixes showing detail only.
 * - If same district but different streets, hides province, city, and district, showing street & detail.
 * - If same city but different districts, hides province and city, showing district & beyond.
 * - If same province but different cities, hides province only, keeping city & beyond.
 * - If across different provinces, shows full addresses with province.
 */
fun formatRouteAddresses(
    startAddress: String?,
    endAddress: String?,
    fallback: String = ""
): Pair<String, String> {
    val cleanStart = startAddress?.toChineseDisplayAddress() ?: fallback
    val cleanEnd = endAddress?.toChineseDisplayAddress() ?: fallback
    if (cleanStart.isBlank() || cleanEnd.isBlank()) {
        return cleanStart to cleanEnd
    }

    if (cleanStart == fallback && cleanEnd == fallback) {
        return fallback to fallback
    }

    if (cleanStart == fallback) {
        val compB = parseChineseAddress(cleanEnd)
        val shortEnd = compB.detail.trim().takeIf { it.isNotEmpty() } ?: compB.streetAndBeyond
        return fallback to shortEnd
    }

    if (cleanEnd == fallback) {
        val compA = parseChineseAddress(cleanStart)
        val shortStart = compA.detail.trim().takeIf { it.isNotEmpty() } ?: compA.streetAndBeyond
        return shortStart to fallback
    }

    val compA = parseChineseAddress(cleanStart)
    val compB = parseChineseAddress(cleanEnd)

    val provA = normalizeAdminName(compA.province)
    val provB = normalizeAdminName(compB.province)
    val cityA = normalizeAdminName(compA.city)
    val cityB = normalizeAdminName(compB.city)
    val distA = normalizeAdminName(compA.district)
    val distB = normalizeAdminName(compB.district)
    val streetA = normalizeAdminName(compA.street)
    val streetB = normalizeAdminName(compB.street)

    val provConflict = (provA != null && provB != null && provA != provB)
    val cityConflict = (cityA != null && cityB != null && cityA != cityB)
    val distConflict = (distA != null && distB != null && distA != distB)
    val streetConflict = (streetA != null && streetB != null && streetA != streetB)

    val sameDist = !provConflict && !cityConflict && !distConflict && (distA != null && distB != null && distA == distB)
    val sameCity = !provConflict && !cityConflict && (sameDist || (cityA != null && cityB != null && cityA == cityB))
    val sameProv = !provConflict && (sameCity || (provA != null && provB != null && provA == provB))
    val sameStreet = sameDist && !streetConflict && (streetA != null && streetB != null && streetA == streetB)

    val (resA, resB) = when {
        sameStreet -> compA.detailOnly to compB.detailOnly
        sameDist -> compA.streetAndBeyond to compB.streetAndBeyond
        sameCity -> compA.districtAndBeyond to compB.districtAndBeyond
        sameProv -> compA.cityAndBeyond to compB.cityAndBeyond
        else -> cleanStart to cleanEnd
    }
    return simplifyAddressLandmark(resA) to simplifyAddressLandmark(resB)
}

/**
 * Formats a parked location address for display.
 * If detail is present after street, shows detail only.
 * Otherwise falls back to street, district, or city.
 */
fun formatParkedAddress(
    parkedAddress: String?,
    fallback: String = ""
): String {
    val clean = parkedAddress?.toChineseDisplayAddress() ?: fallback
    if (clean.isBlank() || clean == fallback) return clean

    val comp = parseChineseAddress(clean)
    if (comp.detail.isNotBlank()) {
        return comp.detail.trim()
    }
    if (!comp.street.isNullOrBlank()) {
        return comp.street.trim()
    }
    if (!comp.district.isNullOrBlank()) {
        return comp.district.trim()
    }
    return comp.city?.trim() ?: clean
}
