package com.matelink.di

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkModuleTest {
    @Test fun normalizedApiToken_removesCopiedWhitespace() {
        assertEquals("token", normalizedApiToken("\n token \r\n"))
        assertEquals("", normalizedApiToken(" \t "))
    }
}
