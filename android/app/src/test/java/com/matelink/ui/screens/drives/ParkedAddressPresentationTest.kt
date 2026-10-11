package com.matelink.ui.screens.drives

import org.junit.Assert.*
import org.junit.Test

class ParkedAddressPresentationTest {
    @Test fun commaDelimitedEnglishSuffixIsRemoved() {
        assertEquals("仓兴街 余杭区", parkedAddressLabel("仓兴街, 余杭区, Yuhang District, Hangzhou"))
    }
    @Test fun alreadyResolvedChineseCandidateWinsOverRawEnglish() {
        assertEquals("仓兴街42号", parkedAddressLabel("42 Cangxing Street, Hangzhou", "仓兴街42号"))
    }
    @Test fun firstChineseCandidateRetainsEndpointPriority() {
        assertEquals("仓兴街", parkedAddressLabel("仓兴街, Hangzhou", "仓前路"))
    }
    @Test fun bilingualAliasesAndRepeatedChineseAreNotStacked() {
        assertEquals("仓兴街 余杭区", parkedAddressLabel("仓兴街 (Cangxing Street)；仓兴街｜余杭区\nYuhang"))
    }
    @Test fun adjacentEnglishAliasesAreTrimmed() {
        assertEquals("杭州市 西湖区", parkedAddressLabel("Hangzhou 杭州市, 西湖区 Xihu District"))
    }
    @Test fun buildingAndRoomIdentifiersArePreserved() {
        assertEquals("A座 余杭区 B1栋 仓兴街42号", parkedAddressLabel("A座, 余杭区 B1栋, 仓兴街42号, Hangzhou"))
    }
    @Test fun nonChineseFallbackIsKeptVerbatimExceptOuterWhitespace() {
        assertEquals("San Jose, CA", parkedAddressLabel(" San Jose, CA ", "Oakland"))
        assertEquals("51.5, -0.1", parkedAddressLabel("51.5, -0.1"))
    }
    @Test fun blankCandidatesUseNextUsefulValue() {
        assertEquals("San Jose", parkedAddressLabel(null, " ", "San Jose"))
        assertNull(parkedAddressLabel(null, " "))
    }
    @Test fun sourcesRemainUnchangedAndDisplayIsIdempotent() {
        val raw = "仓兴街, Hangzhou"
        val label = parkedAddressLabel(raw)
        assertEquals("仓兴街, Hangzhou", raw)
        assertEquals(label, parkedAddressLabel(label))
    }
}
