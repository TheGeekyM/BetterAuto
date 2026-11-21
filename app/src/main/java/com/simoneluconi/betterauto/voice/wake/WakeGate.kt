package com.simoneluconi.betterauto.voice.wake

/**
 * Whether the wake listener may hold the phone's microphone now, and whether what it hears counts. One capture at a
 * time: the assistant (and the questions it asks for a message reply or a call), the phone's own recognizer and a
 * call all take the microphone, so the listener lets it go for them ([Decision.Released]) and takes it back once
 * they are over. While one of our own sounds or a navigation prompt plays (the phone hears the car's speakers), and
 * [tailMs] after, it keeps the microphone but drops the audio ([Decision.Muted]).
 *
 * Level-triggered: every [decide] looks at the inputs afresh, so a turn that never started (voice commands off)
 * cannot leave the listener waiting for an end that never comes. Pure: unit-tested.
 */
class WakeGate(private val tailMs: Long = TAIL_MS) {
    class Inputs(
        /** The wake phrase setting and voice commands both on. */
        val enabled: Boolean,
        /** A call ringing or going on. */
        val call: Boolean,
        /** The assistant is not idle (listening, thinking, answering, waiting for a choice), or asks a question. */
        val assistant: Boolean,
        /** The assistant is about to open a microphone (it waits for ours to close). */
        val yielding: Boolean,
        /** A message being read aloud, or its reply waited for. */
        val reading: Boolean,
        /** One of BetterAuto's own sounds is playing. */
        val ownSound: Boolean,
        /** A navigation app speaks a prompt. */
        val navPrompt: Boolean,
    )

    sealed interface Decision {
        /** The microphone is let go, for [why]. */
        data class Released(val why: String) : Decision

        /** The microphone stays open but nothing heard counts, for [why]. */
        data class Muted(val why: String) : Decision

        data object Listening : Decision
    }

    private var quietUntil = Long.MIN_VALUE

    /** What to do at [now] (ms, a monotonic clock). */
    fun decide(i: Inputs, now: Long): Decision {
        val released = when {
            !i.enabled -> "off"
            i.call -> "call"
            i.assistant || i.yielding -> "assistant"
            i.reading -> "message being read"
            else -> null
        }
        // What was just said or played (the assistant's last words) may still be in the car's speakers
        if (released != null) {
            quietUntil = now + tailMs
            return Decision.Released(released)
        }
        val sound = when {
            i.ownSound -> "our sound"
            i.navPrompt -> "navigation prompt"
            else -> null
        }
        if (sound != null) {
            quietUntil = now + tailMs
            return Decision.Muted(sound)
        }
        return if (now < quietUntil) Decision.Muted("after a sound") else Decision.Listening
    }

    companion object {
        /** After our sounds: the end of a reply still rings in the car, and the music comes back up. */
        const val TAIL_MS = 2_000L
    }
}
