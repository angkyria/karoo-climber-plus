package com.hazzus.karooclimber

import com.hazzus.karooclimber.field.FramePacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    private var clock = 0L
    private val pacer = FramePacer(intervalMs = 5_000, now = { clock })

    @Test
    fun `the first frame goes out immediately`() {
        assertEquals(0L, pacer.waitMs())
    }

    @Test
    fun `later frames wait out the interval`() {
        clock = 1_000
        pacer.sent("a")
        clock = 3_000
        assertEquals(3_000L, pacer.waitMs())
        clock = 6_000
        assertEquals(0L, pacer.waitMs())
        clock = 9_000
        assertEquals(0L, pacer.waitMs())
    }

    @Test
    fun `an identical frame is not new`() {
        pacer.sent(listOf(1, 2))
        assertFalse(pacer.isNew(listOf(1, 2)))
        assertTrue(pacer.isNew(listOf(1, 3)))
    }
}
