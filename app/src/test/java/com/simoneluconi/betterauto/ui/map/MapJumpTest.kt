package com.simoneluconi.betterauto.ui.map

import com.simoneluconi.betterauto.map.MapFix
import com.simoneluconi.betterauto.map.MapSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A position far from the last one drawn is shown at once, not glided to. */
class MapJumpTest {
    private fun fix(lat: Double, lon: Double) = MapFix(lat, lon, null, null, null, MapSource.PHONE, at = 0)

    @Test
    fun aSecondOfDrivingIsNoJump() {
        // About 40 m at 145 km/h
        assertFalse(CarMapState.jumped(fix(30.0, 31.0), fix(30.00036, 31.0)))
    }

    @Test
    fun theGpsBackAfterAStallIsAJump() {
        // 3 km on, as after the phone's GPS stood for a minute and a half on a motorway
        assertTrue(CarMapState.jumped(fix(30.0, 31.0), fix(30.027, 31.0)))
    }
}
