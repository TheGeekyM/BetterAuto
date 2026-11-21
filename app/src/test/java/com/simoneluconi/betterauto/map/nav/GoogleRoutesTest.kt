package com.simoneluconi.betterauto.map.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleRoutesTest {
    // Two steps: north 111 m, then a right turn east; the second starts where the first ends
    private val answer = """
        {"routes":[{"distanceMeters":340,"duration":"90s","legs":[{"steps":[
          {"staticDuration":"20s","polyline":{"encodedPolyline":"_atqG_y|u@gE?"},
           "navigationInstruction":{"maneuver":"DEPART","instructions":"Head north on Via Roma"}},
          {"staticDuration":"40s","polyline":{"encodedPolyline":"ggtqG_y|u@?oK?gE"},
           "navigationInstruction":{"maneuver":"TURN_RIGHT","instructions":"Turn right onto Corso Italia\nDestination will be on the left"}}
        ]}]}]}
    """.trimIndent()

    @Test
    fun readsTheStepsJoinedWithAnArrival() {
        val route = GoogleRoutes.parse(answer)!!
        // The shared point once: 2 + 3 - 1
        assertEquals(4, route.size)
        assertEquals(listOf(ManeuverKind.STRAIGHT, ManeuverKind.RIGHT, ManeuverKind.ARRIVE), route.maneuvers.map { it.kind })
        assertEquals(listOf(0, 1, 3), route.maneuvers.map { it.beginIndex })
        val turn = route.maneuvers[1]
        assertEquals("Turn right onto Corso Italia", turn.instruction)
        assertEquals("Corso Italia", turn.street)
        assertEquals("turn right onto Corso Italia Destination will be on the left", turn.spokenSoon)
        assertEquals("Via Roma", route.maneuvers[0].street)
        // The steps' 60 s without traffic stretched to the route's 90 s with it
        assertEquals(90.0, route.durationS, 0.001)
        assertEquals(30.0, route.secondsTo(route.cumulative[1]), 0.5)
        assertEquals(90.0, route.secondsTo(route.lengthM + 1000), 0.5)
    }

    @Test
    fun noRouteIsNull() {
        assertNull(GoogleRoutes.parse("{}"))
    }

    @Test
    fun roundaboutExitFromTheSentence() {
        val json = answer.replace("TURN_RIGHT", "ROUNDABOUT_RIGHT").replace("Turn right onto", "At the roundabout, take the 2nd exit onto")
        assertEquals(2, GoogleRoutes.parse(json)!!.maneuvers[1].roundaboutExit)
    }
}
