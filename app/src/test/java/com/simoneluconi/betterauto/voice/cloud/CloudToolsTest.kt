package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudToolsTest {
    private val call = { name: String, type: String? -> if (name == "Nobody") null else VoiceCommand.Call(listOf(name), type) }
    private fun r(name: String, vararg input: Pair<String, Any?>) = CloudTools.result(name, mapOf(*input), call)
    private fun cmd(c: VoiceCommand) = CloudResult.Command(c)

    @Test
    fun `every tool runs an existing command`() {
        assertEquals(cmd(VoiceCommand.Play("Amr Diab")), r("play_music", "query" to " Amr Diab "))
        assertEquals(cmd(VoiceCommand.Pause), r("media", "action" to "pause"))
        assertEquals(cmd(VoiceCommand.Resume), r("media", "action" to "resume"))
        assertEquals(cmd(VoiceCommand.Next), r("media", "action" to "next"))
        assertEquals(cmd(VoiceCommand.Previous), r("media", "action" to "previous"))
        assertEquals(cmd(VoiceCommand.Open(Section.RADIO)), r("open_section", "section" to "radio"))
        assertEquals(cmd(VoiceCommand.Open(Section.SMART_HOME)), r("open_section", "section" to "smart_home"))
        assertEquals(cmd(VoiceCommand.Quiet(true)), r("quiet_mode", "on" to true))
        assertEquals(cmd(VoiceCommand.ReadNotifications), r("read_notifications"))
        assertEquals(cmd(VoiceCommand.Navigate("City Stars")), r("navigate", "place" to "City Stars"))
        assertEquals(cmd(VoiceCommand.AddStop("gas station")), r("add_stop", "place" to "gas station"))
        assertEquals(cmd(VoiceCommand.SkipStop), r("skip_stop"))
        assertEquals(cmd(VoiceCommand.EndNavigation), r("end_navigation"))
        assertEquals(cmd(VoiceCommand.Call(listOf("Ahmed Ali"), "mobile")), r("call_contact", "name" to "Ahmed Ali", "number" to "mobile"))
        assertEquals(cmd(VoiceCommand.Call(listOf("Ahmed Ali"), null)), r("call_contact", "name" to "Ahmed Ali", "number" to "main"))
        assertEquals(cmd(VoiceCommand.AcPower(false)), r("set_ac", "on" to false))
        assertEquals(cmd(VoiceCommand.AcAuto), r("set_climate_auto"))
        assertEquals(cmd(VoiceCommand.AcTemp(value = 22)), r("set_temperature", "value" to 22))
        assertEquals(cmd(VoiceCommand.AcTemp(delta = -2)), r("set_temperature", "delta" to -2))
        assertEquals(cmd(VoiceCommand.Fan(2)), r("set_fan", "delta" to 2))
        assertEquals(cmd(VoiceCommand.Volume("mute")), r("set_volume", "action" to "mute"))
        assertEquals(cmd(VoiceCommand.Tires), r("tyres_status"))
        assertEquals(cmd(VoiceCommand.Trips), r("recent_trips"))
        assertEquals(cmd(VoiceCommand.Cancel), r("cancel"))
        assertEquals(CloudResult.Answer("Laravel is a PHP framework."), r("answer", "text" to "**Laravel** is a PHP framework [1]."))
        assertEquals(CloudResult.NotUnderstood, r("not_understood"))
    }

    @Test
    fun `numbers are clamped and nonsense is not understood`() {
        assertEquals(cmd(VoiceCommand.AcTemp(value = 32)), r("set_temperature", "value" to 45))
        assertEquals(cmd(VoiceCommand.AcTemp(value = 16)), r("set_temperature", "value" to 3))
        assertEquals(cmd(VoiceCommand.AcTemp(delta = 8)), r("set_temperature", "delta" to 20))
        assertEquals(cmd(VoiceCommand.Fan(-7)), r("set_fan", "delta" to -30))
        listOf(r("set_temperature"), r("set_temperature", "delta" to 0), r("set_fan", "delta" to 0), r("media", "action" to "rewind"),
            r("open_section", "section" to "garage"), r("set_volume", "action" to "louder"), r("play_music", "query" to " "),
            r("call_contact", "name" to "Nobody", "number" to "main"), r("rm_rf"), r("answer", "text" to " "),
        ).forEach { assertEquals(CloudResult.NotUnderstood, it) }
    }

    @Test
    fun `strict schemas within the API's limits`() {
        assertEquals(20, CloudTools.specs.size)
        assertEquals(CloudTools.specs.size, CloudTools.specs.map { it.name }.toSet().size)
        val optional = CloudTools.specs.sumOf { s -> s.properties.keys.count { it !in s.required } }
        assertTrue(optional <= 24)
        CloudTools.specs.forEach { assertEquals(false, it.schema()["additionalProperties"]) }
    }

    @Test
    fun `answers are said sentence by sentence`() {
        assertEquals(listOf("Laravel is a PHP framework.", "Taylor Otwell made it!"), CloudTools.sentences("Laravel is a PHP framework. Taylor Otwell made it!"))
        assertEquals(listOf("لارافيل فريمورك PHP.", "عمله تايلور أوتويل؟"), CloudTools.sentences("لارافيل فريمورك PHP. عمله تايلور أوتويل؟"))
        assertEquals(listOf("no stop"), CloudTools.sentences("no stop"))
        assertEquals("See the docs.", CloudTools.speakable("See https://x.y [the docs](https://x.y)."))
    }
}
