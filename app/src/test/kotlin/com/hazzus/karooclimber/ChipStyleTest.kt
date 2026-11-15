package com.hazzus.karooclimber

import com.hazzus.karooclimber.overlay.ChipStyle
import com.hazzus.karooclimber.palette.GradePalettes
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipStyleTest {

    private val karoo = GradePalettes.byId("karoo")

    @Test
    fun `on or before a climb the pill takes the grade color`() {
        assertEquals(karoo.chunkColor(8.0), ChipStyle.background(8.0, karoo))
    }

    @Test
    fun `with no climb ahead the pill is solid dark`() {
        assertEquals(ChipStyle.IDLE, ChipStyle.background(null, karoo))
        assertEquals(0xFF, ChipStyle.IDLE ushr 24) // opaque, like the navigation pill
    }

    @Test
    fun `text stays readable on light and dark pills`() {
        assertEquals(ChipStyle.TEXT_DARK, ChipStyle.textOn(0xFFFFE600.toInt()))
        assertEquals(ChipStyle.TEXT_LIGHT, ChipStyle.textOn(0xFFD0021B.toInt()))
        assertEquals(ChipStyle.TEXT_LIGHT, ChipStyle.textOn(ChipStyle.IDLE))
    }

    @Test
    fun `beside the navigation pill the chip says one thing`() {
        assertEquals("2/5", ChipStyle.compactText(completed = 2, total = 5, toStart = null, grade = null))
        assertEquals("1.2", ChipStyle.compactText(completed = 2, total = 5, toStart = "1.2", grade = null))
        assertEquals("7%", ChipStyle.compactText(completed = 2, total = 5, toStart = null, grade = "7%"))
    }
}
