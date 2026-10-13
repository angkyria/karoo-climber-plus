package com.hazzus.karooclimber

import com.hazzus.karooclimber.ui.distanceText
import com.hazzus.karooclimber.ui.distanceWithUnitText
import com.hazzus.karooclimber.ui.elevationText
import com.hazzus.karooclimber.ui.kmText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitsTest {

    private val defaultLocale = Locale.getDefault()

    // "%.1f" follows the default locale, as on the device: pin it for stable expectations
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun `distance text switches from meters to one-decimal km at 1 km`() {
        assertEquals("400", distanceText(400.0, imperial = false))
        assertEquals("999", distanceText(999.9, imperial = false))
        assertEquals("1.4", distanceText(1400.0, imperial = false))
    }

    @Test
    fun `imperial distance text switches from feet to miles at a tenth of a mile`() {
        assertEquals("328", distanceText(100.0, imperial = true))
        assertEquals("0.9", distanceText(1448.4, imperial = true))
    }

    @Test
    fun `elevation text truncates to whole meters or feet`() {
        assertEquals("412", elevationText(412.9, imperial = false))
        assertEquals("1354", elevationText(412.9, imperial = true))
        assertEquals("-12", elevationText(-12.4, imperial = false))
    }

    @Test
    fun `km text always has one decimal`() {
        assertEquals("0.7", kmText(700.0, imperial = false))
        assertEquals("0.4", kmText(700.0, imperial = true))
    }

    @Test
    fun `distance with unit text carries the unit label`() {
        assertEquals("400 m", distanceWithUnitText(400.0, imperial = false))
        assertEquals("2.0 km", distanceWithUnitText(2000.0, imperial = false))
        assertEquals("328 ft", distanceWithUnitText(100.0, imperial = true))
        assertEquals("1.2 mi", distanceWithUnitText(2000.0, imperial = true))
    }
}
