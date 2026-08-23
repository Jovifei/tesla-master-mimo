package com.matelink.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourVoltApiUrlTest {
    @Test
    fun acceptsHttpsRootAndNormalizesTrailingSlash() {
        assertEquals(
            "https://api.example.com/",
            validatedJourVoltApiBaseUrl(" https://api.example.com/ ")
        )
    }

    @Test
    fun rejectsAllHttpOriginsIncludingLocalDebugOrigins() {
        assertNull(validatedJourVoltApiBaseUrl("http://127.0.0.1:18090"))
        assertNull(validatedJourVoltApiBaseUrl("http://api.example.com"))
    }

    @Test
    fun allowsOnlyLocalHttpWhenExplicitlyRequested() {
        assertEquals(
            "http://127.0.0.1:18090/",
            validatedJourVoltApiBaseUrl("http://127.0.0.1:18090", allowLocalHttp = true)
        )
        assertNull(validatedJourVoltApiBaseUrl("http://api.example.com", allowLocalHttp = true))
    }

    @Test
    fun rejectsNonRootPathsQueriesFragmentsAndMalformedValues() {
        assertNull(validatedJourVoltApiBaseUrl("https://api.example.com/v1"))
        assertNull(validatedJourVoltApiBaseUrl("https://api.example.com/?x=1"))
        assertNull(validatedJourVoltApiBaseUrl("https://api.example.com/#callback"))
        assertNull(validatedJourVoltApiBaseUrl("not-a-url"))
    }
}
