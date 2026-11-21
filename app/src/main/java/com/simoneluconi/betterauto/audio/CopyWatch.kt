package com.simoneluconi.betterauto.audio

/**
 * While copying: decides, once a second, whether the phone's speaker goes back to a player the copy does not
 * hear, and whether it is muted again when the copy hears sound. Pure, so it can be tested off Android.
 *
 * A player that refuses to be copied (Spotify's AAudio streams) is heard by nothing while the phone is muted, so it
 * gets the speaker back. A player that mutes *itself* when the phone is muted (YouTube at media volume zero) is the
 * other kind: given the speaker it plays again, the copy hears it, the phone is muted, it goes quiet again. Re-muting
 * it each time made the music in the car play a second out of every ten. So a player that goes quiet again soon after
 * being muted again keeps the phone's speaker for the rest of the connection: heard on both, never cut.
 */
class CopyWatch(private val silentMs: Long, private val checkMs: Long) {
    enum class Action { NONE, SPEAKER_BACK, MUTE_AGAIN }

    /** The phone's speaker was given back. */
    var speakerBack = false
        private set

    /** Why the last [Action.SPEAKER_BACK] happened: the player mutes itself rather than refusing the copy. */
    var mutesItself = false
        private set

    private var playingSince = 0L
    private var mutedAgainAt = 0L
    private var mutedAgainFor: String? = null
    private val keepSpeaker = HashSet<String>()

    /**
     * One check at [now]: [player] is the package playing (null when nothing plays), [lastSound] when the copy last
     * carried the players' sound (0 if never).
     */
    fun check(now: Long, player: String?, lastSound: Long): Action {
        playingSince = if (player != null) playingSince.takeIf { it != 0L } ?: now else 0L
        if (!speakerBack) {
            val quietFor = now - maxOf(lastSound, playingSince)
            if (player == null || quietFor < silentMs) return Action.NONE
            speakerBack = true
            mutesItself = player == mutedAgainFor && now - mutedAgainAt < silentMs + SELF_MUTE_SLACK_MS
            if (mutesItself) keepSpeaker += player
            return Action.SPEAKER_BACK
        }
        if (now - lastSound >= checkMs * 2) return Action.NONE
        // Heard again: the very player that goes quiet when muted keeps the speaker
        if (player != null && player in keepSpeaker) return Action.NONE
        speakerBack = false
        mutesItself = false
        mutedAgainAt = now
        mutedAgainFor = player
        return Action.MUTE_AGAIN
    }

    private companion object {
        /**
         * Muted again, a self-muting player is quiet within a second and the watch notices about [silentMs] later
         * (9 to 10 s in the car); a copyable player is heard for far longer than this before any quiet passage.
         */
        const val SELF_MUTE_SLACK_MS = 6_000L
    }
}
