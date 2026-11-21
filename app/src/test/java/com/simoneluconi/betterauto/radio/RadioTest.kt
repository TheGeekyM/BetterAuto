package com.simoneluconi.betterauto.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioTest {
    @Test
    fun oneStationPerNameWorkingAndMostVotedFirst() {
        val json = """[
          {"stationuuid":"a","name":"Nogoum FM","url_resolved":"https://a","votes":5,"lastcheckok":1,"tags":"pop,arabic"},
          {"stationuuid":"b","name":"Nile FM","url":"http://b","url_resolved":"","votes":90,"lastcheckok":0,"tags":""},
          {"stationuuid":"c","name":"nogoum  fm ","url_resolved":"https://c","votes":50,"lastcheckok":1,"tags":""},
          {"stationuuid":"d","name":"No address","url_resolved":"","url":"","votes":99,"lastcheckok":1}
        ]"""
        val list = Radio.parse(json)
        assertEquals(listOf("c", "b"), list.map { it.id })
        assertEquals("http://b", list[1].url)
        assertEquals("nogoum fm", list[0].name)
    }
}
