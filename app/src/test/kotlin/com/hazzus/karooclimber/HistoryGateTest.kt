package com.hazzus.karooclimber

import com.hazzus.karooclimber.data.HistoryGate
import com.hazzus.karooclimber.data.HistorySource
import com.hazzus.karooclimber.data.HistoryStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGateTest {

    @Test
    fun `nothing records while the field is not placed`() {
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = false, rideActive = true, demoMode = false))
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = false, rideActive = true, demoMode = true))
    }

    @Test
    fun `demo mode wins over a running ride`() {
        assertEquals(HistorySource.DEMO, HistoryGate.sourceFor(fieldPlaced = true, rideActive = true, demoMode = true))
        assertEquals(HistorySource.DEMO, HistoryGate.sourceFor(fieldPlaced = true, rideActive = false, demoMode = true))
    }

    @Test
    fun `a placed field records a running ride only`() {
        assertEquals(HistorySource.RIDE, HistoryGate.sourceFor(fieldPlaced = true, rideActive = true, demoMode = false))
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = true, rideActive = false, demoMode = false))
    }

    @Test
    fun `a ride streams distance, elevation and ascent`() {
        assertEquals(HistoryStreams.RIDE, HistoryGate.streamsFor(HistorySource.RIDE, viewRunning = true))
        assertEquals(HistoryStreams.RIDE, HistoryGate.streamsFor(HistorySource.RIDE, viewRunning = false))
    }

    @Test
    fun `a field on screen before the ride streams only the elevation`() {
        assertEquals(HistoryStreams.ELEVATION, HistoryGate.streamsFor(HistorySource.NONE, viewRunning = true))
        assertEquals(HistoryStreams.NONE, HistoryGate.streamsFor(HistorySource.NONE, viewRunning = false))
    }

    @Test
    fun `demo mode needs no Karoo streams`() {
        assertEquals(HistoryStreams.NONE, HistoryGate.streamsFor(HistorySource.DEMO, viewRunning = true))
    }

    @Test
    fun `switching sources clears the history but pausing does not`() {
        val gate = HistoryGate()
        assertFalse(gate.switchTo(HistorySource.RIDE))
        assertFalse(gate.switchTo(HistorySource.NONE))
        assertFalse(gate.switchTo(HistorySource.RIDE))
        assertTrue(gate.switchTo(HistorySource.DEMO))
        assertFalse(gate.switchTo(HistorySource.DEMO))
        assertFalse(gate.switchTo(HistorySource.NONE))
        assertTrue(gate.switchTo(HistorySource.RIDE))
    }
}
