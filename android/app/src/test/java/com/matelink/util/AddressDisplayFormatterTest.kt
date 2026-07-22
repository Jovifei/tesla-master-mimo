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
}
