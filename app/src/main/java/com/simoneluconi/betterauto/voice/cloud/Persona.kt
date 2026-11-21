package com.simoneluconi.betterauto.voice.cloud

/**
 * Who the online assistant is: a warm, playful Egyptian woman who knows the driver, calls him by his name or a
 * nickname (often, not in every sentence) with a light Egyptian "دلع", and stays short because he is driving. The
 * name and nicknames are the driver's own (settings, on the phone only). Pure, so the texts are tested.
 */
object Persona {
    /** The name first, then the nicknames ("Sam", "Sammy, Sam the Man"); blanks dropped. */
    fun names(name: String, nicknames: String): List<String> =
        (listOf(name) + nicknames.split(',', '،')).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** What to call him now: a nickname most of the time, the name otherwise; null when none is set. */
    fun callName(name: String, nicknames: String, random: kotlin.random.Random = kotlin.random.Random): String? {
        val all = names(name, nicknames)
        val nick = all.drop(1)
        return if (nick.isNotEmpty() && random.nextFloat() < 0.7f) nick.random(random) else all.firstOrNull()
    }

    fun prompt(name: String, nicknames: String): String = buildString {
        append("Your personality: a warm, playful Egyptian woman who knows the driver well and is fond of him. ")
        val all = names(name, nicknames)
        if (all.isNotEmpty()) {
            append("He is ${all.first()}")
            if (all.size > 1) append(", nicknamed ${all.drop(1).joinToString(", ")}")
            append(". Call him by his name or a nickname often, but not in every sentence. ")
        }
        append("In Egyptian Arabic use light, affectionate دلع (\"من عينيا\", \"حاضر يا قمر\", \"يا حبيبي\"), never " +
            "overdone; in English be just as warm. Always short: he is driving.")
    }

    /** The voice's style, for Gemini's text-to-speech. */
    fun ttsStyle(arabic: Boolean): String =
        if (arabic) "Say it warmly and playfully in natural Egyptian Arabic, like talking to someone you're fond of: "
        else "Say it warmly and playfully, like talking to someone you're fond of: "

    enum class DayPart(val key: String) { MORNING("morning"), EVENING("evening"), NIGHT("night") }

    /** Morning 5 to 12, evening 12 to 22, night after. Also the name of the recorded greetings ("morning.p1.ogg"). */
    fun dayPart(hour: Int): DayPart = when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..21 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }

    /** "صباح الخير يا سام" / "Good morning, Sam", for a voice that speaks text. */
    fun greeting(part: DayPart, who: String?, arabic: Boolean): String {
        val to = who?.let { if (arabic) " يا $it" else ", $it" }.orEmpty()
        return if (arabic) when (part) {
            DayPart.MORNING -> "صباح الخير$to، أؤمرني"
            DayPart.EVENING -> "مساء الخير$to، عايز إيه؟"
            DayPart.NIGHT -> "سهرانين$to؟ أؤمرني"
        } else when (part) {
            DayPart.MORNING -> "Good morning$to, what can I do for you?"
            DayPart.EVENING -> "Good evening$to, what do you need?"
            DayPart.NIGHT -> "Still up$to? What do you need?"
        }
    }
}
