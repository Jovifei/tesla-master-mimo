package com.matelink.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaCallbackReplayGuardTest {
    @Test
    fun acceptsNewTicket() {
        assertFalse(
            shouldIgnoreTeslaCallbackTicket(
                ticket = "ticket-new",
                inFlightTicket = null,
                handledTicket = null
            )
        )
    }

    @Test
    fun ignoresDuplicateInFlightTicket() {
        assertTrue(
            shouldIgnoreTeslaCallbackTicket(
                ticket = "ticket-same",
                inFlightTicket = "ticket-same",
                handledTicket = null
            )
        )
    }

    @Test
    fun ignoresAlreadyHandledTicket() {
        assertTrue(
            shouldIgnoreTeslaCallbackTicket(
                ticket = "ticket-same",
                inFlightTicket = null,
                handledTicket = "ticket-same"
            )
        )
    }

    @Test
    fun rejectsBlankTicket() {
        assertTrue(
            shouldIgnoreTeslaCallbackTicket(
                ticket = "",
                inFlightTicket = null,
                handledTicket = null
            )
        )
    }
}
