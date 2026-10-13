package com.hazzus.karooclimber

import com.hazzus.karooclimber.palette.GradePalettes
import org.junit.Assert.assertEquals
import org.junit.Test

class GradePaletteTest {

    private val karoo = GradePalettes.byId("karoo")
    private val turbo = GradePalettes.byId("turbo")

    @Test
    fun `downhill chunks use the descent color on palettes without negative bands`() {
        assertEquals(GradePalettes.descentColor, karoo.chunkColor(-3.0))
        assertEquals(karoo.colorFor(0.0), karoo.chunkColor(0.0))
        assertEquals(karoo.colorFor(9.0), karoo.chunkColor(9.0))
    }

    @Test
    fun `turbo colors downhill chunks with its own negative bands`() {
        assertEquals(0xFF5783E9.toInt(), turbo.chunkColor(-7.0))
        assertEquals(0xFF401C4C.toInt(), turbo.chunkColor(-20.0))
    }

    @Test
    fun `descent color keeps its previous rgb value`() {
        assertEquals(0xFF507896.toInt(), GradePalettes.descentColor)
    }
}
