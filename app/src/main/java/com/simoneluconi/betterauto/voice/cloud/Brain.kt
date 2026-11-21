package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.settings.VoiceBrain
import com.simoneluconi.betterauto.voice.CommandParser
import com.simoneluconi.betterauto.voice.VoiceCommand

/**
 * Which way a voice command goes, decided per turn. Pure, so the choice is tested.
 *
 * Online needs the build's two keys, the internet, and Android 13 (we feed the phone's recognizer our own audio to
 * keep the fast local path alive while recording the command for the cloud). In Auto a clear command the phone's
 * parser understands runs at once ([fastPath]); only the rest goes to Gemini and Claude.
 */
object Brain {
    /** [cloud]: the command is recorded for Gemini; [localFirst]: a clear local command wins; [why] for the log. */
    data class Route(val cloud: Boolean, val localFirst: Boolean, val why: String)

    fun route(setting: VoiceBrain, hasKeys: Boolean, internet: Boolean, sdk: Int): Route = when {
        setting == VoiceBrain.OFFLINE -> Route(false, true, "offline by setting")
        !hasKeys -> Route(false, true, "offline: this build has no Gemini/Anthropic keys")
        !internet -> Route(false, true, "offline: no internet")
        sdk < 33 -> Route(false, true, "offline: Android 13 or later needed")
        setting == VoiceBrain.ONLINE -> Route(true, false, "online by setting")
        else -> Route(true, true, "auto: local first, online for the rest")
    }

    /**
     * A command clear enough to run without asking the cloud: fixed words the parser knows. A call only when the
     * name fits one contact exactly; a drive only to a place driven to before (a new place may need Claude's help to
     * be told apart from a question).
     */
    fun fastPath(command: VoiceCommand?, recentPlaces: Collection<String>): Boolean = when (command) {
        null -> false
        is VoiceCommand.Call -> command.exact && command.candidates.size == 1
        is VoiceCommand.Navigate -> known(command.place, recentPlaces)
        is VoiceCommand.AddStop -> known(command.place, recentPlaces)
        // Dictated words: Gemini hears them better than the phone
        is VoiceCommand.Reply -> false
        else -> true
    }

    private fun known(place: String, recent: Collection<String>): Boolean {
        val p = CommandParser.normalize(place)
        return p.isNotEmpty() && recent.any { CommandParser.normalize(it).let { r -> r == p || r.startsWith("$p ") } }
    }
}
