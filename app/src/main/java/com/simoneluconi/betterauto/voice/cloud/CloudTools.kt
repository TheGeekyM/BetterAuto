package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.voice.VoiceCommand

/** What Claude decided: one of the assistant's own commands, a spoken answer, or nothing understood. */
sealed interface CloudResult {
    data class Command(val command: VoiceCommand) : CloudResult
    data class Answer(val text: String) : CloudResult
    data object NotUnderstood : CloudResult
}

/**
 * The tools Claude may call, one per [VoiceCommand] (strict JSON schemas as plain maps), and the way back from a
 * tool call to the command BetterAuto already knows how to run. Pure: tested without Android or the SDK.
 */
object CloudTools {
    class Spec(val name: String, val description: String, val properties: Map<String, Map<String, Any>>, val required: List<String>) {
        fun schema(): Map<String, Any> =
            mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false)
    }

    private fun string(description: String) = mapOf("type" to "string", "description" to description)
    private fun choice(vararg values: String) = mapOf("type" to "string", "enum" to values.toList())
    private val bool = mapOf("type" to "boolean")
    private val int = mapOf("type" to "integer")
    private val none = emptyMap<String, Map<String, Any>>()

    private val Sections = mapOf(
        "home" to Section.HOME, "music" to Section.MUSIC, "map" to Section.MAP, "phone" to Section.PHONE,
        "vehicle" to Section.VEHICLE, "notifications" to Section.NOTIFICATIONS, "settings" to Section.SETTINGS,
        "smart_home" to Section.SMART_HOME, "radio" to Section.RADIO,
    )

    /** 20 strict tools, the API's most; at most 24 optional parameters (here 2). */
    val specs: List<Spec> = listOf(
        Spec("play_music", "Play a song, artist, album, playlist or podcast.", mapOf("query" to string("What to play, as said.")), listOf("query")),
        Spec("media", "Pause, resume, next or previous track.", mapOf("action" to choice("pause", "resume", "next", "previous")), listOf("action")),
        Spec("open_section", "Open a screen of the car's launcher (radio is the radio screen).",
            mapOf("section" to choice(*Sections.keys.toTypedArray())), listOf("section")),
        Spec("quiet_mode", "Turn quiet mode (no notification sounds or banners) on or off.", mapOf("on" to bool), listOf("on")),
        // One tool for reading and replying: the API takes 20 strict tools at most
        Spec("read_notifications", "Read the latest messages and notifications aloud. To reply to a message instead, give " +
            "reply_to (the sender or chat as said, or \"last\" for the latest message) and text (the reply, word for word " +
            "as dictated, in the language spoken; empty to ask the driver what to say).",
            mapOf("reply_to" to string("Who to reply to, or \"last\"."), "text" to string("The reply as dictated.")), emptyList()),
        Spec("navigate", "Start navigation to a place: an address, a town, a shop or restaurant, home, work.",
            mapOf("place" to string("The place, as said.")), listOf("place")),
        Spec("add_stop", "Add a stop on the way of the current trip.", mapOf("place" to string("The place, as said.")), listOf("place")),
        Spec("skip_stop", "Skip the next stop of the trip.", none, emptyList()),
        Spec("end_navigation", "Stop the navigation.", none, emptyList()),
        Spec("call_contact", "Call a contact of the phone.",
            mapOf("name" to string("The contact's name, spelled as in the contact list when it is there."),
                "number" to choice("main", "mobile", "home", "work")),
            listOf("name", "number")),
        Spec("set_ac", "Turn the car's climate control on or off.", mapOf("on" to bool), listOf("on")),
        Spec("set_climate_auto", "Put the car's climate control on automatic.", none, emptyList()),
        Spec("set_temperature", "Set the cabin temperature: value in °C (16 to 32), or delta in degrees (+ warmer, - cooler).",
            mapOf("value" to int, "delta" to int), emptyList()),
        Spec("set_fan", "Make the fan stronger (+) or weaker (-), in steps.", mapOf("delta" to int), listOf("delta")),
        Spec("set_volume", "The car's volume: up, down, mute or unmute.", mapOf("action" to choice("up", "down", "mute", "unmute")), listOf("action")),
        Spec("tyres_status", "Check the tyre pressures.", none, emptyList()),
        Spec("recent_trips", "List the recent trips / destinations.", none, emptyList()),
        Spec("cancel", "The driver wants nothing: never mind, cancel, close.", none, emptyList()),
        Spec("not_understood", "Only when the transcript is unintelligible.", none, emptyList()),
        Spec("answer", "Answer a question or chat: general knowledge, explanations, advice, small talk, news " +
            "(after web_search when it needs fresh information).",
            mapOf("text" to string("The spoken answer for a driver: 1 to 3 short sentences, at most about 40 words, in " +
                "the driver's language and dialect (Egyptian Arabic if they spoke Arabic). No lists, markdown, links or sources.")),
            listOf("text")),
    )

    /**
     * A tool call as a [CloudResult]. [call] finds the contact (the phone's own fuzzy matcher). Anything out of
     * place (unknown tool or value, no such contact) is not understood; numbers are clamped.
     */
    fun result(name: String, input: Map<String, Any?>, call: (String, String?) -> VoiceCommand?): CloudResult {
        val command: VoiceCommand? = when (name) {
            "play_music" -> text(input["query"])?.let { VoiceCommand.Play(it) }
            "media" -> when (input["action"]) {
                "pause" -> VoiceCommand.Pause
                "resume" -> VoiceCommand.Resume
                "next" -> VoiceCommand.Next
                "previous" -> VoiceCommand.Previous
                else -> null
            }
            "open_section" -> Sections[input["section"]]?.let { VoiceCommand.Open(it) }
            "quiet_mode" -> (input["on"] as? Boolean)?.let { VoiceCommand.Quiet(it) }
            "read_notifications" -> {
                val to = text(input["reply_to"])
                val said = (input["text"] as? String)?.trim().orEmpty()
                if (to == null && said.isEmpty()) VoiceCommand.ReadNotifications
                else VoiceCommand.Reply(to?.takeUnless { it.equals("last", ignoreCase = true) }, said)
            }
            "navigate" -> text(input["place"])?.let { VoiceCommand.Navigate(it) }
            "add_stop" -> text(input["place"])?.let { VoiceCommand.AddStop(it) }
            "skip_stop" -> VoiceCommand.SkipStop
            "end_navigation" -> VoiceCommand.EndNavigation
            "call_contact" -> text(input["name"])?.let { call(it, (input["number"] as? String)?.takeIf { n -> n != "main" }) }
            "set_ac" -> (input["on"] as? Boolean)?.let { VoiceCommand.AcPower(it) }
            "set_climate_auto" -> VoiceCommand.AcAuto
            "set_temperature" -> {
                val value = (input["value"] as? Number)?.toInt()?.coerceIn(16, 32)
                val delta = (input["delta"] as? Number)?.toInt()?.coerceIn(-8, 8) ?: 0
                if (value != null) VoiceCommand.AcTemp(value = value) else delta.takeIf { it != 0 }?.let { VoiceCommand.AcTemp(delta = it) }
            }
            "set_fan" -> (input["delta"] as? Number)?.toInt()?.coerceIn(-7, 7)?.takeIf { it != 0 }?.let { VoiceCommand.Fan(it) }
            "set_volume" -> (input["action"] as? String)?.takeIf { it in setOf("up", "down", "mute", "unmute") }?.let { VoiceCommand.Volume(it) }
            "tyres_status" -> VoiceCommand.Tires
            "recent_trips" -> VoiceCommand.Trips
            "cancel" -> VoiceCommand.Cancel
            "answer" -> return answer(input["text"] as? String)
            else -> null
        }
        return command?.let { CloudResult.Command(it) } ?: CloudResult.NotUnderstood
    }

    /** Free text (an answer, or Claude replying without a tool) as it can be said; blank is not understood. */
    fun answer(text: String?): CloudResult = speakable(text.orEmpty()).let { if (it.isEmpty()) CloudResult.NotUnderstood else CloudResult.Answer(it) }

    /** Links, URLs, markdown and citation marks out, whitespace collapsed, capped. */
    fun speakable(text: String): String = text
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\[\\d+(,\\s*\\d+)*]"), "")
        .replace(Regex("(?m)^\\s*([-*•]|\\d+\\.)\\s+"), "")
        .replace(Regex("[*_#`>]"), "")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([.,!?؟،])"), "$1")
        .trim()
        .take(400)

    /** An answer in sentences, so the first can be spoken while the rest is still being made. */
    fun sentences(text: String): List<String> =
        Regex("[^.!?؟]+[.!?؟]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList().ifEmpty { listOf(text) }

    private fun text(v: Any?) = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
