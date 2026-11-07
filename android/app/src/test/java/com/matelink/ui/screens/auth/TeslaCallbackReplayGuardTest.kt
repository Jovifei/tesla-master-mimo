package com.matelink.ui.screens.auth

import org.junit.Assert.assertEquals
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

    @Test
    fun alreadyHandledTicketWithSessionOpensDashboard() {
        assertEquals(
            TeslaCallbackReplayDecision.OpenDashboard,
            teslaCallbackReplayDecision(
                ticket = "ticket-same",
                inFlightTicket = null,
                handledTicket = "ticket-same",
                hasSession = true
            )
        )
    }

    @Test
    fun inFlightTicketIsIgnoredEvenWhenSessionExists() {
        assertEquals(
            TeslaCallbackReplayDecision.Ignore,
            teslaCallbackReplayDecision(
                ticket = "ticket-same",
                inFlightTicket = "ticket-same",
                handledTicket = null,
                hasSession = true
            )
        )
    }

    @Test
    fun newTicketWithAnExistingSessionStillRequiresExchange() {
        assertEquals(
            TeslaCallbackReplayDecision.Process,
            teslaCallbackReplayDecision(
                ticket = "ticket-new",
                inFlightTicket = null,
                handledTicket = "ticket-old",
                hasSession = true
            )
        )
    }
}
