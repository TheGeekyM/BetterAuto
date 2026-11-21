package com.simoneluconi.betterauto.settings

/** A section that can sit in the app bar. Settings is not one of them: it is always there. */
enum class RailItem(val section: Section) {
    HOME(Section.HOME),
    MUSIC(Section.MUSIC),
    RADIO(Section.RADIO),
    MAP(Section.MAP),
    PHONE(Section.PHONE),
    VEHICLE(Section.VEHICLE),
    NOTIFICATIONS(Section.NOTIFICATIONS),
}

/** Where an item goes: its own button, one of two grouped buttons, or nowhere. */
enum class RailSlot { BAR, GROUP_1, GROUP_2, HIDDEN }

data class RailEntry(val item: RailItem, val slot: RailSlot)

/** The app bar as the driver arranged it: order and place of every item. */
object RailLayout {
    val Default = listOf(
        RailEntry(RailItem.HOME, RailSlot.BAR),
        RailEntry(RailItem.MUSIC, RailSlot.BAR),
        RailEntry(RailItem.RADIO, RailSlot.BAR),
        RailEntry(RailItem.MAP, RailSlot.BAR),
        RailEntry(RailItem.PHONE, RailSlot.BAR),
        RailEntry(RailItem.VEHICLE, RailSlot.BAR),
        // The bell in the status bar already opens them
        RailEntry(RailItem.NOTIFICATIONS, RailSlot.HIDDEN),
    )

    val Groups = listOf(RailSlot.GROUP_1, RailSlot.GROUP_2)

    fun encode(entries: List<RailEntry>) = entries.joinToString(",") { "${it.item.name}:${it.slot.name}" }

    /** Saved layout, with items added in later versions appended in their default place. */
    fun decode(saved: String?): List<RailEntry> {
        val parsed = saved?.split(",")?.mapNotNull { part ->
            val (item, slot) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val railItem = RailItem.entries.firstOrNull { it.name == item } ?: return@mapNotNull null
            RailEntry(railItem, RailSlot.entries.firstOrNull { it.name == slot } ?: RailSlot.BAR)
        }.orEmpty().distinctBy { it.item }
        return parsed + Default.filter { d -> parsed.none { it.item == d.item } }
    }

    fun move(entries: List<RailEntry>, item: RailItem, delta: Int): List<RailEntry> {
        val from = entries.indexOfFirst { it.item == item }
        val to = (from + delta).coerceIn(0, entries.lastIndex)
        if (from < 0 || from == to) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun place(entries: List<RailEntry>, item: RailItem, slot: RailSlot) =
        entries.map { if (it.item == item) it.copy(slot = slot) else it }

    /**
     * Media apps for the bar, before trimming to the room there is: pinned first, then recent ones, or
     * [suggested] while there is no history. The app the Music section follows ([followed]) is left out,
     * pinned or not: its button would only open Music again.
     */
    fun mediaApps(pinned: List<String>, recent: List<String>, followed: String?, suggested: () -> List<String>): List<String> =
        (pinned + recent.filter { it !in pinned }).ifEmpty { suggested() }.filter { it != followed }
}

/** How the clock in the status bar is shown. */
enum class ClockStyle { HIDDEN, AUTO, H24, H12 }

/** Which microphone voice commands listen to: the car's when it has one, or always the phone's. */
enum class MicSource { CAR, PHONE }

/**
 * How readily the wake phrase wakes the assistant, as [com.simoneluconi.betterauto.voice.wake.WakeMatcher.threshold]'s
 * 0..1 ([value]). NORMAL wants the whole phrase; EAGER also takes it with a syllable lost (more caught, more false
 * wakes); STRICT only a clean one.
 */
enum class WakeSensitivity(val value: Float) { STRICT(0.25f), NORMAL(0.5f), EAGER(0.75f) }

/** Who speaks: one of the recorded voices (folders of `assets/voice/`), or the phone's text-to-speech. */
enum class SpokenVoice(val folder: String?) {
    AUTONOE("autonoe"), SORAYA("soraya"), NADINE("nadine"), PHONE(null);

    companion object {
        /** Until the driver picks one. Autonoe speaks Arabic only, and may not be in the build: [FALLBACK] then. */
        val DEFAULT = AUTONOE
        /** Speaks for a recorded voice in a language (or a build) it has no recordings for. */
        val FALLBACK = SORAYA
    }
}

/**
 * Where voice commands are understood. Auto: a clear command on the phone at once, anything else (questions, loose
 * phrasing) online with Gemini and Claude when the build has their keys and the phone is online; Online: always
 * online when it can be; Offline: only the phone, as before.
 */
enum class VoiceBrain { AUTO, ONLINE, OFFLINE }

/** Claude model of the online assistant: Haiku answers fastest, Sonnet understands loose phrasing better. */
enum class ClaudeModel(val id: String) { HAIKU("claude-haiku-5-5"), SONNET("claude-sonnet-5-5"), OPUS("claude-opus-5-5") }

/** Gemini model that turns the recorded command into text. */
enum class SttModel(val id: String) { FLASH_LITE("gemini-3.5-flash-lite"), FLASH("gemini-2.5-flash") }

/** Gemini's prebuilt voices for spoken answers, female ones first. */
enum class GeminiVoice(val female: Boolean) {
    ZEPHYR(true), KORE(true), LEDA(true), AOEDE(true), CALLIRRHOE(true), AUTONOE(true), DESPINA(true), ERINOME(true),
    LAOMEDEIA(true), ACHERNAR(true), GACRUX(true), PULCHERRIMA(true), VINDEMIATRIX(true), SULAFAT(true),
    PUCK(false), CHARON(false), FENRIR(false), ORUS(false), ENCELADUS(false), IAPETUS(false), UMBRIEL(false),
    ALGIEBA(false), ALGENIB(false), RASALGETHI(false), ALNILAM(false), SCHEDAR(false), ACHIRD(false),
    ZUBENELGENUBI(false), SADACHBIA(false), SADALTAGER(false);

    /** The name the API takes ("Kore"). */
    val apiName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** How the date in the status bar is shown. */
enum class DateStyle { HIDDEN, SHORT, LONG, NUMERIC }
