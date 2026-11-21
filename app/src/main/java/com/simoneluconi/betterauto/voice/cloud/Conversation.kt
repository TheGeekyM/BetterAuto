package com.simoneluconi.betterauto.voice.cloud

/**
 * Short memory for follow-ups ("and who made it?"): the last [maxTurns] exchanges as text, forgotten after
 * [idleMs] without a turn. In memory only.
 */
class Conversation(
    private val maxTurns: Int = 6,
    private val idleMs: Long = 5 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Turn(val user: String, val assistant: String)

    private val turns = ArrayDeque<Turn>()
    private var last = 0L

    @Synchronized
    fun history(): List<Turn> {
        if (now() - last > idleMs) turns.clear()
        return turns.toList()
    }

    @Synchronized
    fun add(user: String, assistant: String) {
        if (now() - last > idleMs) turns.clear()
        turns.addLast(Turn(user, assistant.ifBlank { "(done)" }))
        while (turns.size > maxTurns) turns.removeFirst()
        last = now()
    }
}
