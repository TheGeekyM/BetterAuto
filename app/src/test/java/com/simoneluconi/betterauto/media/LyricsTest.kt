package com.simoneluconi.betterauto.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    @Test
    fun timedLinesInOrderWithTheLineBeingSung() {
        val lines = Lyrics.parseSynced("[00:12.30] second\n[00:01.5]first\n[01:00.00][00:30.00] chorus\nno time here")
        assertEquals(listOf(1500L, 12300L, 30000L, 60000L), lines.map { it.timeMs })
        assertEquals(listOf("first", "second", "chorus", "chorus"), lines.map { it.text })
        assertEquals(-1, Lyrics.lineAt(lines, 1000))
        assertEquals(1, Lyrics.lineAt(lines, 20000))
        assertEquals(3, Lyrics.lineAt(lines, 99000))
    }
}
