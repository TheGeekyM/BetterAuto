package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.voice.VoiceClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersonaTest {
    @Test
    fun `names and what to call him`() {
        assertEquals(listOf("Sam", "Sammy", "Sam the Man"), Persona.names(" Sam ", "Sammy, Sam the Man,"))
        assertEquals(listOf("Sam", "Sammy"), Persona.names("Sam", "Sammy، Sam"))
        assertNull(Persona.callName("", ""))
        assertEquals("Sam", Persona.callName("Sam", ""))
        val called = (0 until 200).map { Persona.callName("Sam", "Sammy", Random(it)) }.toSet()
        assertEquals(setOf("Sam", "Sammy"), called)
    }

    @Test
    fun `the persona names him only when set`() {
        assertTrue(Persona.prompt("Sam", "Sammy").contains("He is Sam, nicknamed Sammy."))
        assertFalse(Persona.prompt("", "").contains("He is"))
        assertTrue(Persona.ttsStyle(arabic = true).contains("Egyptian Arabic"))
    }

    @Test
    fun `time of day greetings`() {
        assertEquals(Persona.DayPart.MORNING, Persona.dayPart(7))
        assertEquals(Persona.DayPart.EVENING, Persona.dayPart(12))
        assertEquals(Persona.DayPart.EVENING, Persona.dayPart(21))
        assertEquals(Persona.DayPart.NIGHT, Persona.dayPart(23))
        assertEquals(Persona.DayPart.NIGHT, Persona.dayPart(3))
        assertEquals("صباح الخير يا Sam، أؤمرني", Persona.greeting(Persona.DayPart.MORNING, "Sam", arabic = true))
        assertEquals("Good evening, Sam, what do you need?", Persona.greeting(Persona.DayPart.EVENING, "Sam", arabic = false))
        assertEquals("Good morning, what can I do for you?", Persona.greeting(Persona.DayPart.MORNING, null, arabic = false))
    }

    @Test
    fun `personal takes are picked about 70 percent of the time`() {
        val present = setOf("right.ogg", "right.p1.ogg", "right.p2.ogg", "left.ogg", "morning.p1.ogg", "greet1.ogg")
        assertEquals("left.ogg", VoiceClips.pick("left", present))
        assertEquals("morning.p1.ogg", VoiceClips.pick("morning", present))
        assertNull(VoiceClips.pick("evening", present))
        assertNull(VoiceClips.pick("u_turn", present))
        val picks = (0 until 1000).map { VoiceClips.pick("right", present, Random(it))!! }
        val personal = picks.count { it != "right.ogg" }
        assertTrue("$personal", personal in 620..780)
        assertEquals(setOf("right.ogg", "right.p1.ogg", "right.p2.ogg"), picks.toSet())
        // "rightish.p1.ogg" is not a take of "right"
        assertEquals("right.ogg", VoiceClips.pick("right", setOf("right.ogg", "right_x.p1.ogg")))
    }
}
