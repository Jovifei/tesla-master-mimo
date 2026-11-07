package com.matelink.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressDisplayFormatterTest {

    @Test
    fun removesEnglishAdministrativeSuffixWhenChineseAddressIsAvailable() {
        assertEquals("仓兴街", "仓兴街, Yuhang District".toChineseDisplayAddress())
    }

    @Test
    fun translatesEnglishPoiAndDistrict() {
        assertEquals("全家便利店 余杭区", "FamilyMart, Yuhang District".toChineseDisplayAddress())
    }

    @Test
    fun keepsNonChineseAddressesReadable() {
        assertEquals("San Jose, CA", "San Jose, CA".toChineseDisplayAddress())
    }

    @Test
    fun nullAddressReturnsNull() {
        assertNull(null.toChineseDisplayAddress())
    }

    @Test
    fun emptyAddressReturnsNull() {
        assertNull("".toChineseDisplayAddress())
    }

    @Test
    fun whitespaceAddressReturnsNull() {
        assertNull("   ".toChineseDisplayAddress())
    }

    @Test
    fun syntheticUnrecognisedTextIsPreserved() {
        assertEquals("synthetic-address-###", "synthetic-address-###".toChineseDisplayAddress())
    }

    @Test
    fun punctuationAnomalyDoesNotThrowOrInventAddress() {
        assertEquals("?? ;;;", " ?? ;;; ".toChineseDisplayAddress())
    }

    @Test
    fun conciseChineseAddressIsNotDuplicated() {
        assertEquals("合成街", "合成街".toChineseDisplayAddress())
    }

    @Test
    fun sameDistrictAndStreetOmitsAllPrefixesShowingDetailOnly() {
        val start = "浙江省杭州市余杭区仓前街道重庆鸡公煲(万通中心店)万通中心"
        val end = "浙江省杭州市余杭区仓前街道钱腾云都"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("万通中心", dispStart)
        assertEquals("钱腾云都", dispEnd)
    }

    @Test
    fun simplifiesLandmarksAndRemovesVerboseShopBranches() {
        val start = "浙江省杭州市余杭区良渚街道鞠婆婆绵绵冰(美塘广场杭州总店)"
        val end = "浙江省杭州市西湖区文新街道西溪天堂"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("余杭区良渚街道美塘广场", dispStart)
        assertEquals("西湖区文新街道西溪天堂", dispEnd)
    }

    @Test
    fun sameDistrictDifferentStreetsShowsStreetAndBeyond() {
        val start = "浙江省杭州市余杭区仓前街道好呷温州小吃泡炸万通中心"
        val end = "浙江省杭州市余杭区五常街道西溪印象城"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("仓前街道好呷温州小吃泡炸万通中心", dispStart)
        assertEquals("五常街道西溪印象城", dispEnd)
    }

    @Test
    fun sameCityDifferentDistrictsShowsDistrictAndBeyond() {
        val start = "浙江省杭州市余杭区仓前街道"
        val end = "浙江省杭州市西湖区转塘街道"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("余杭区仓前街道", dispStart)
        assertEquals("西湖区转塘街道", dispEnd)
    }

    @Test
    fun municipalitySameCityOmitsMunicipality() {
        val start = "北京市朝阳区望京街10号"
        val end = "北京市海淀区中关村南大街1号"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("朝阳区望京街10号", dispStart)
        assertEquals("海淀区中关村南大街1号", dispEnd)
    }

    @Test
    fun sameProvinceDifferentCitiesKeepsCityOnly() {
        val start = "浙江省杭州市余杭区仓前街道"
        val end = "浙江省宁波市鄞州区首南街道"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("杭州市余杭区仓前街道", dispStart)
        assertEquals("宁波市鄞州区首南街道", dispEnd)
    }

    @Test
    fun differentProvincesKeepsFullAddresses() {
        val start = "浙江省杭州市余杭区仓前街道"
        val end = "江苏省苏州市工业园区金鸡湖大道"
        val (dispStart, dispEnd) = formatRouteAddresses(start, end)
        assertEquals("浙江省杭州市余杭区仓前街道", dispStart)
        assertEquals("江苏省苏州市工业园区金鸡湖大道", dispEnd)
    }

    @Test
    fun singleKnownEndpointIsGracefullyShortenedWhenOtherSideUnknown() {
        val start = "浙江省杭州市余杭区仓前街道好呷温州小吃泡炸万通中心"
        val (dispStart, dispEnd) = formatRouteAddresses(start, "未知", "未知")
        assertEquals("好呷温州小吃泡炸万通中心", dispStart)
        assertEquals("未知", dispEnd)
    }

    @Test
    fun parkedAddressStripsProvinceCityDistrictAndStreetShowingDetailOnly() {
        val raw = "浙江省杭州市余杭区仓前街道青象传媒杭州精诚电力设备有限公司"
        assertEquals("青象传媒杭州精诚电力设备有限公司", formatParkedAddress(raw))
    }

    @Test
    fun parkedAddressPreservesStreetWhenNoDetail() {
        val raw = "浙江省杭州市余杭区仓前街道"
        assertEquals("仓前街道", formatParkedAddress(raw))
    }

    @Test
    fun parkedAddressPreservesCityWhenNoDistrictOrStreet() {
        val raw = "杭州市"
        assertEquals("杭州市", formatParkedAddress(raw))
    }
}
