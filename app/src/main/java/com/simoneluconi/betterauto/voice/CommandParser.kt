package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.settings.Section
import java.text.Normalizer

/** What a spoken phrase asks for. */
sealed interface VoiceCommand {
    /**
     * Call one of [candidates]; [numberType] is "mobile", "home", "work" or null for the main number.
     * [exact] when every spoken word is a word of the name: otherwise it's a guess to confirm.
     */
    data class Call(val candidates: List<String>, val numberType: String?, val exact: Boolean = true) : VoiceCommand
    data class Play(val query: String) : VoiceCommand
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data class Open(val section: Section) : VoiceCommand
    data class Quiet(val on: Boolean) : VoiceCommand
    data object ReadNotifications : VoiceCommand
    /**
     * Reply [text] to the last message from [contact] (as heard: matched against the chats later), or to the last
     * message at all when null. A blank [text]: the assistant asks what to say.
     */
    data class Reply(val contact: String?, val text: String) : VoiceCommand
    data object Cancel : VoiceCommand
    /** Drive to [place]: an address, a town, a shop or a restaurant ("roadhouse"), "casa". */
    data class Navigate(val place: String) : VoiceCommand
    /** A stop on the way to the destination ("fermati al benzinaio"). */
    data class AddStop(val place: String) : VoiceCommand
    data object SkipStop : VoiceCommand
    data object EndNavigation : VoiceCommand
    /** The climate on or off ("شغّل التكييف"). */
    data class AcPower(val on: Boolean) : VoiceCommand
    data object AcAuto : VoiceCommand
    /** To [value] °C, or [delta] degrees warmer (+) or cooler (−). */
    data class AcTemp(val value: Int? = null, val delta: Int = 0) : VoiceCommand
    data class Fan(val delta: Int) : VoiceCommand
    /** The head unit's volume: "up", "down", "mute" or "unmute". */
    data class Volume(val dir: String) : VoiceCommand
    data object Tires : VoiceCommand
    data object Trips : VoiceCommand
}

/**
 * Turns recognized text into a command. The words are fixed per language (Italian, English, Arabic), the
 * names come from the phone (contacts): unknown text gives no command instead of a guess. Pure logic,
 * so it can be tested; tolerant to recognition slips ("Mario Rosi" finds "Mario Rossi").
 */
class CommandParser(private val contacts: List<String>) {
    private val index = contacts.map { it to normalize(it) }

    /** Why [text] gave no command, when that can be told better than "not understood". */
    sealed interface Miss {
        data class ContactNotFound(val name: String) : Miss
        data class UnknownPlace(val name: String) : Miss
    }

    fun miss(text: String): Miss? {
        val t = polite(normalize(text))
        startAfter(t, CallWords)?.takeIf { it.isNotBlank() }?.let { return Miss.ContactNotFound(it) }
        startAfter(t, OpenWords)?.takeIf { it.isNotBlank() }?.let { return Miss.UnknownPlace(it) }
        return null
    }

    /** The command in [text], or null if it isn't one (yet: a partial result may still grow). */
    fun parse(text: String): VoiceCommand? {
        val t = polite(normalize(text))
        if (t.isEmpty()) return null
        // Before the car: "اقفل" alone closes the panel, "اقفل التكييف" is not only dismiss words
        if (dismisses(t)) return VoiceCommand.Cancel
        // Before the car and the music: what is dictated may name anything ("قوله شغّل التكييف")
        reply(text)?.let { return it }
        // Before the music: "شغّل التكييف" is the climate, not a song
        carCommand(t)?.let { return it }

        startAfter(t, CallWords)?.let { rest -> return call(rest) }
        if (t in PauseWords) return VoiceCommand.Pause
        if (t in ResumeWords) return VoiceCommand.Resume
        if (t in NextWords) return VoiceCommand.Next
        if (t in PreviousWords) return VoiceCommand.Previous
        if (t in ReadWords) return VoiceCommand.ReadNotifications
        if (t in QuietOn) return VoiceCommand.Quiet(true)
        if (t in QuietOff) return VoiceCommand.Quiet(false)
        if (t in SkipStopWords) return VoiceCommand.SkipStop
        if (t in EndNavigationWords) return VoiceCommand.EndNavigation
        startAfter(t, OpenWords)?.let { rest -> sectionOf(rest)?.let { return VoiceCommand.Open(it) } }
        sectionOf(t)?.let { return VoiceCommand.Open(it) }
        // "vai a" a section was handled above: anything else after it is a place to drive to
        place(t, AddStopRegex)?.let { return sectionOf(it)?.let { s -> VoiceCommand.Open(s) } ?: VoiceCommand.AddStop(it) }
        place(t, NavigateRegex)?.let { return VoiceCommand.Navigate(it) }
        startAfter(t, PlayWords)?.takeIf { it.isNotBlank() }?.let { query ->
            // "metti la musica" / "play music" just resumes
            if (query in GenericMusic) return VoiceCommand.Resume
            return VoiceCommand.Play(query.removePrefix("la ").removePrefix("il ").removePrefix("i "))
        }
        return null
    }

    /**
     * "Nothing, close", "no thanks", "خلاص مش عايز حاجة اقفلي": the whole sentence is dismiss phrases, with fillers
     * ("no", "بس") between them but at least one phrase, so "بس" and "stop" alone stay a pause.
     */
    private fun dismisses(t: String): Boolean {
        var rest = t
        var any = false
        while (rest.isNotEmpty()) {
            val phrase = CancelByLength.firstOrNull { rest == it || rest.startsWith("$it ") }
            val word = phrase ?: rest.substringBefore(' ')
            if (phrase != null) any = true else if (word !in DismissFillers) return false
            rest = rest.removePrefix(word).trim()
        }
        return any
    }

    /**
     * "رد على أحمد قوله أنا جاي", "ابعت لماما إني اتأخرت", "reply to Mario saying I'm late", "rispondi a Giulia che arrivo":
     * a reply to the last message from someone; "رد عليه قوله…", "reply saying…": to the last message at all. The
     * text keeps the words as said (capitals, accents, punctuation); none, and the assistant will ask for it.
     */
    private fun reply(text: String): VoiceCommand.Reply? {
        val raw = text.trim().split(Spaces).filter { it.isNotEmpty() }
        val norm = raw.map { normalize(it).replace("ـ", "") }
        // Spelled one way for the fixed words; the name keeps its letters, as contacts are matched
        val words = norm.map(::fold)
        var at = 0
        while (at < words.size && words[at] in PoliteWords) at++
        val verb = ReplyVerbs.firstOrNull { v -> words.startsWith(v.words, at) } ?: return null
        at += verb.words.size
        fun textFrom(i: Int) = raw.drop(i).joinToString(" ").trim()
        fun separatorAt(i: Int) = ReplySeparators.firstOrNull { words.startsWith(it, i) }
        if (!verb.name) {
            val marker = ReplyMarkers.firstOrNull { words.startsWith(it, at) }
            if (marker != null) at += marker.size
            separatorAt(at)?.let { return VoiceCommand.Reply(null, textFrom(at + it.size)) }
            if (at >= words.size) return VoiceCommand.Reply(null, "")
            // "رد" followed by anything else is not surely a reply ("رد" may answer a call): only with "عليه" or "قوله"
            return if (marker != null || verb.freeText) VoiceCommand.Reply(null, textFrom(at)) else null
        }
        if (at >= words.size) return null
        // "ابعت لأحمد": the "to" sticks to the name
        val name = norm.drop(at).toMutableList()
        verb.attached?.let { prefix ->
            when {
                name.first() == prefix -> name.removeAt(0)
                name.first().startsWith(prefix) -> name[0] = name.first().removePrefix(prefix)
                // "ابعت موقعي": not to someone
                else -> return null
            }
        }
        if (name.isEmpty()) return null
        val offset = words.size - at - name.size // a lone "ل" dropped
        fun named(n: List<String>, said: String): VoiceCommand.Reply {
            val who = n.joinToString(" ").trim()
            return VoiceCommand.Reply(who.takeUnless { fold(it) in NullNames || it.isEmpty() }, said)
        }
        // The name ends where "قوله" / "saying" / "che" begins
        for (i in 1 until name.size) {
            separatorAt(at + offset + i)?.let { sep -> return named(name.take(i), textFrom(at + offset + i + sep.size)) }
        }
        // No such word: the longest start that names a contact, the rest is the text
        for (n in minOf(3, name.size - 1) downTo 1) {
            val candidate = name.take(n).joinToString(" ")
            if (matchContacts(candidate).isNotEmpty()) return named(name.take(n), textFrom(at + offset + n))
        }
        // Only a name ("رد على أحمد"): the assistant asks what to say
        return if (name.size <= 3) named(name, "") else null
    }

    private fun List<String>.startsWith(prefix: List<String>, from: Int): Boolean =
        size - from >= prefix.size && prefix.indices.all { this[from + it] == prefix[it] }

    /**
     * "Portami al roadhouse", "naviga verso piazza del campo", "voglio andare all'ospedale", "take me to the
     * station", "fermati al benzinaio": the place after the words that ask to drive (or stop) and the preposition
     * that follows them.
     */
    private fun place(t: String, regex: Regex): String? {
        val m = regex.matchEntire(t) ?: return null
        var place = m.groupValues[2].trim()
        // "the" / "il" left before the place in English or after a bare verb
        place = place.split(' ').dropWhile { it in Articles }.joinToString(" ")
        if (place.startsWith("لل")) place = "ال" + place.drop(2)
        // "vai avanti", "go back": a direction, not a place; "vai alla prossima canzone": the music, not a place
        if (place in NotPlaces || place.split(' ').any { it in MusicWords }) return null
        return place.takeIf { it.isNotBlank() }
    }

    /**
     * The car's own controls and data, by the words in the sentence rather than a fixed phrase: "علّي التكييف شوية",
     * "turn the AC on please", "come sono le gomme".
     */
    private fun carCommand(text: String): VoiceCommand? {
        val t = fold(text)
        // "Take me to the tyre shop" is a place to drive to
        if (NavigateRegex.matches(t) || AddStopRegex.matches(t)) return null
        val words = t.split(' ').toSet()
        // Several words: whole words in a row ("a c" is not in "mamma casa")
        fun has(keys: Collection<String>) = keys.any { key -> fold(key).let { k -> if (' ' in k) " $t ".contains(" $k ") else k in words } }
        // Egyptian verbs take prefixes and suffixes ("تفتحي", "اقفليه", "زوديلي"): their roots, inside a word
        fun stem(roots: List<String>) = words.any { w -> roots.any { w.contains(it) } }
        val up = has(UpWords) || stem(UpRoots)
        val down = has(DownWords) || stem(DownRoots)
        val number = Digits.find(t.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString(""))
            ?.value?.toIntOrNull()?.takeIf { it in 16..32 }
        return when {
            has(TireWords) -> VoiceCommand.Tires
            has(TripWords) -> VoiceCommand.Trips
            has(MuteWords) -> VoiceCommand.Volume("mute")
            has(UnmuteWords) && has(SoundWords) -> VoiceCommand.Volume("unmute")
            has(SoundWords) && up -> VoiceCommand.Volume("up")
            has(SoundWords) && down -> VoiceCommand.Volume("down")
            t in Louder -> VoiceCommand.Volume("up")
            t in Quieter -> VoiceCommand.Volume("down")
            has(FanWords) && up -> VoiceCommand.Fan(1)
            has(FanWords) && down -> VoiceCommand.Fan(-1)
            has(TempWords) && number != null -> VoiceCommand.AcTemp(value = number)
            has(TempWords) && (up || has(Warmer)) -> VoiceCommand.AcTemp(delta = 1)
            has(TempWords) && (down || has(Cooler)) -> VoiceCommand.AcTemp(delta = -1)
            t in WarmerAlone -> VoiceCommand.AcTemp(delta = 1)
            t in CoolerAlone -> VoiceCommand.AcTemp(delta = -1)
            !has(ClimateWords) -> null
            has(OffWords) || stem(OffRoots) -> VoiceCommand.AcPower(false)
            has(AutoWords) -> VoiceCommand.AcAuto
            number != null -> VoiceCommand.AcTemp(value = number)
            down -> VoiceCommand.AcTemp(delta = -1)
            up -> VoiceCommand.AcTemp(delta = 1)
            // The climate named with no other wish ("ممكن تفتحي التكييف", "عايز التكييف"): on
            else -> VoiceCommand.AcPower(true)
        }
    }

    private fun call(rest: String): VoiceCommand? {
        if (rest.isBlank()) return null
        var name = rest
        var type: String? = null
        for ((suffixes, kind) in NumberTypes) {
            for (suffix in suffixes) {
                if (name.endsWith(" $suffix")) {
                    name = name.removeSuffix(" $suffix").trim()
                    type = kind
                }
            }
        }
        // "sul", "al", "a" left between the name and the number type
        name = name.split(' ').dropLastWhile { it in Fillers }.dropWhile { it in Fillers }.joinToString(" ")
        // "اتصل بأحمد": the "by" sticks to the name; without it when that names someone exactly ("بسمة" stays)
        if (name.startsWith("ب")) call(name.drop(1), type)?.takeIf { it.exact }?.let { return it }
        return call(name, type)
    }

    private fun call(name: String, type: String?): VoiceCommand.Call? {
        val matches = matchContacts(name)
        if (matches.isEmpty()) return null
        val said = name.split(' ').filter { it.isNotBlank() }
        val exact = matches.size == 1 && said.all { word -> normalize(matches.first()).split(' ').contains(word) }
        return VoiceCommand.Call(matches, type, exact)
    }

    /** A call to [name] as the online assistant heard it (its words, not a sentence), or null when no contact fits. */
    fun callCommand(name: String, numberType: String?): VoiceCommand.Call? =
        normalize(name).takeIf { it.isNotEmpty() }?.let { call(it, numberType) }

    /** Contacts matching [spoken], best first: exact words, then close spellings. */
    fun matchContacts(spoken: String): List<String> {
        if (spoken.isBlank()) return emptyList()
        val scored = index.map { (name, norm) -> name to score(spoken, norm) }.filter { it.second >= MIN_SCORE }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        // Clear winner, or every name about as good (the driver will choose)
        return scored.filter { it.second >= best - AMBIGUITY }.sortedByDescending { it.second }.map { it.first }.distinct().take(5)
    }

    private fun score(spoken: String, name: String): Double {
        val said = spoken.split(' ').filter { it.isNotBlank() }
        val words = name.split(' ').filter { it.isNotBlank() }
        if (said.isEmpty() || words.isEmpty()) return 0.0
        // Each spoken word against its best word of the name; every spoken word must fit, not just on average
        val perWord = said.map { s -> words.maxOf { w -> similarity(s, w) } }
        if (perWord.min() < MIN_WORD) return 0.0
        val coverage = said.size.toDouble() / words.size
        return perWord.average() * (0.85 + 0.15 * coverage.coerceAtMost(1.0))
    }

    companion object {
        private const val MIN_SCORE = 0.78
        private const val MIN_WORD = 0.75
        private const val AMBIGUITY = 0.04

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Marks, "")
                .replace(NotWord, " ")
                .replace(Spaces, " ")
                .trim()

        // Compiled once: every contact name and every partial result goes through normalize()
        private val Marks = Regex("\\p{M}")
        private val NotWord = Regex("[^\\p{L}\\p{N} ]")
        private val Spaces = Regex("\\s+")

        /** Arabic words as the text will be after [normalize]: written as they are spelled. */
        private fun ar(vararg words: String) = words.map(::normalize)

        /** People and the recognizer write ى for ي and ة for ه: one spelling for matching words (places keep theirs). */
        private fun fold(s: String) = s.replace('ى', 'ي').replace('ة', 'ه')

        /** 1 for equal words, falling with the edit distance. */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val d = levenshtein(a, b)
            return 1.0 - d.toDouble() / maxOf(a.length, b.length)
        }

        private fun levenshtein(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

        /**
         * The driver's answer to the assistant's own yes-or-no question ("أحمد بيتصل، ترد؟"): true for yes ("أيوه",
         * "رد", "answer"), false for no ("لا", "اقفل", "decline"), null for anything else. The first of them counts;
         * a long sentence is no answer (someone talking in the car).
         */
        fun yesNo(text: String): Boolean? {
            val words = fold(normalize(text)).split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > MAX_ANSWER_WORDS) return null
            for (i in words.indices) {
                for (phrase in AnswerPhrases) {
                    if (phrase.first.size <= words.size - i && phrase.first.indices.all { words[i + it] == phrase.first[it] }) return phrase.second
                }
            }
            return null
        }

        /** A dictated reply that is only "no", "cancel", "never mind": nothing is sent. */
        fun cancels(text: String): Boolean {
            val t = fold(normalize(text))
            if (t.isEmpty()) return false
            if (t in NoReply) return true
            val words = t.split(' ')
            return words.size <= MAX_ANSWER_WORDS && words.all { it in NoFillers } ||
                CommandParser(emptyList()).dismisses(normalize(text))
        }

        private const val MAX_ANSWER_WORDS = 5

        /** A word ([fold]ed) said alone or with others like it: a refusal. */
        private val NoFillers = setOf("no", "nope", "cancel", "annulla") + ar("لا", "لأ", "لاء", "كنسل", "الغي").map(::fold)
        private val NoReply = (
            setOf("don t reply", "do not reply", "don t send", "do not send", "non rispondere", "non mandare", "non inviare", "non mandarlo") +
                ar("مش عايز ارد", "مش عاوز ارد", "متبعتش", "متبعتيش", "متردش", "مترديش", "بلاش", "خليها", "سيبها", "مش هرد")
            ).map(::fold).toSet()

        /** Longest phrases first, so "no thanks" is not read as a plain "no" before "مش دلوقتي" etc. */
        private val AnswerPhrases: List<Pair<List<String>, Boolean>> = (
            (listOf("yes", "yeah", "yep", "yup", "sure", "answer", "answer it", "pick up", "pick it up", "accept", "ok", "okay",
                "si", "rispondi", "certo", "va bene") +
                ar("أيوه", "ايوه", "أيوة", "ايوة", "ايوا", "اه", "آه", "رد", "ردي", "رد عليه", "ردي عليه", "رد عليها", "ردي عليها",
                    "افتح", "افتحي", "تمام", "ماشي", "طبعا", "نعم", "اكيد", "يلا")).map { it to true } +
                (listOf("no", "nope", "decline", "reject", "hang up", "ignore", "ignore it", "not now", "cancel", "later",
                    "rifiuta", "riaggancia", "ignora", "non ora", "dopo", "annulla") +
                    ar("لا", "لأ", "لاء", "اقفل", "اقفلي", "اقفل عليه", "اقفلي عليه", "ارفض", "ارفضي", "مش دلوقتي", "مش عايز",
                        "مش عاوز", "سيبه", "سيبيه", "سيبها", "كنسل", "الغي", "متردش", "مترديش", "بعدين", "طنش", "طنشي")).map { it to false }
            ).map { (p, yes) -> fold(normalize(p)).split(' ') to yes }.sortedByDescending { it.first.size }

        /** [name]: a person follows; [attached]: written on the name ("لأحمد"); [freeText]: any words after are the text. */
        private class ReplyVerb(val words: List<String>, val name: Boolean, val attached: String? = null, val freeText: Boolean = false)

        private fun verb(phrase: String, name: Boolean, attached: String? = null, freeText: Boolean = false) =
            ReplyVerb(fold(normalize(phrase)).split(' '), name, attached?.let { fold(normalize(it)) }, freeText)

        private val ReplyVerbs = (
            listOf("رد على", "ردي على", "ترد على", "تردي على", "reply to", "respond to", "send a message to", "send message to",
                "write to", "text", "message", "rispondi a", "rispondi ad", "scrivi a", "scrivi ad", "manda un messaggio a",
                "invia un messaggio a").map { verb(it, name = true) } +
                listOf("ابعت رسالة", "ابعتي رسالة", "ابعت", "ابعتي", "تبعت", "تبعتي").map { verb(it, name = true, attached = "ل") } +
                listOf("reply", "respond", "rispondi").map { verb(it, name = false, freeText = true) } +
                listOf("رد", "ردي", "ترد", "تردي").map { verb(it, name = false) }
            ).sortedByDescending { it.words.size }

        /** After "رد" / "reply": the message meant is the last one ("رد عليه", "reply to it"). */
        private val ReplyMarkers = listOf(
            "عليه", "عليها", "عليهم", "على الرسالة", "على الرسالة دي", "على آخر رسالة", "to it", "to that", "to the message",
            "to this message", "to the last message", "al messaggio", "all ultimo messaggio", "a questo messaggio",
        ).map { fold(normalize(it)).split(' ') }.sortedByDescending { it.size }

        /** Where the name ends and the text begins: "قوله", "إن", "saying", "che"... */
        private val ReplySeparators = listOf(
            "قوله", "قولها", "قولهم", "قوليله", "قوليلها", "قوليلهم", "وقوله", "وقولها", "وقولهم", "قول له", "قولي له", "قول لها",
            "قولي لها", "وقول له", "وقول لها", "قل له", "قل لها", "إن", "إني", "بإن", "بإني",
            "saying", "that", "say", "and say", "and tell him", "and tell her", "with the text",
            "che", "dicendo", "dicendogli", "dicendole", "scrivendo", "e digli", "e dille", "digli", "dille",
        ).map { fold(normalize(it)).split(' ') }.sortedByDescending { it.size }

        /** A "name" that means the last message, not a person. */
        private val NullNames = (
            listOf("it", "him", "her", "them", "that", "this", "the message", "this message", "the last message", "last message",
                "messaggio", "il messaggio", "lui", "lei", "l ultimo messaggio", "ultimo messaggio") +
                listOf("الرسالة", "الرسالة دي", "آخر رسالة", "اخر رسالة", "عليه", "عليها")
            ).map { fold(normalize(it)) }.toSet()

        private val PoliteWords = (listOf("please", "can you", "could you") + listOf("ممكن", "لو سمحت", "لو سمحتي", "يا ريت"))
            .flatMap { fold(normalize(it)).split(' ') }.toSet()

        private fun startAfter(text: String, prefixes: List<String>): String? =
            prefixes.firstOrNull { text == it || text.startsWith("$it ") }?.let { text.removePrefix(it).trim() }

        private fun sectionOf(text: String): Section? =
            Sections.entries.firstOrNull { (words, _) -> words.any { fold(it) == fold(text) } }?.value

        /** "ممكن … لو سمحتي" around a command: courtesy, not part of it. */
        private fun polite(t: String): String {
            var s = t
            PoliteBefore.firstOrNull { s.startsWith("$it ") }?.let { s = s.removePrefix(it).trim() }
            PoliteAfter.firstOrNull { s.endsWith(" $it") }?.let { s = s.removeSuffix(it).trim() }
            return s
        }

        private val PoliteBefore = ar("لو سمحتي", "لو سمحت", "يا ريت", "ممكن", "please")
        private val PoliteAfter = ar("لو سمحتي", "لو سمحت", "من فضلك", "please")

        private val CallWords = listOf("chiama", "telefona a", "telefona", "fai una chiamata a", "call", "phone", "dial") +
            ar("اتصل ب", "اتصل على", "اتصل", "كلملي", "كلم", "رن على", "رنلي على", "اتصال ب", "اتصال", "اطلب")
        private val PlayWords = listOf("metti", "riproduci", "ascolta", "fammi sentire", "play", "put on", "listen to") +
            ar("شغل لي", "شغللي", "شغلنا", "شغل", "شغلي", "سمعني", "سمعنا", "عايز اسمع", "عاوز اسمع", "اسمع")
        private val OpenWords = listOf("apri", "vai a", "vai su", "vai alla", "vai al", "passa a", "passa alla", "passa al", "mostra", "open", "go to", "show") +
            ar("افتحيلي", "افتحي", "تفتحيلي", "تفتحي", "تفتحلي", "تفتح", "افتح", "افتحلي", "روحي على", "روح على", "اعرضي", "اعرض", "وريني", "ورينى", "طلعلي")
        private val Fillers = setOf("sul", "al", "a", "il", "su", "on", "the", "at", "di") +
            ar("على", "رقم", "في")
        private val NumberTypes = listOf(
            listOf("cellulare", "cell", "mobile", "telefonino") + ar("موبايل", "الموبايل", "المحمول") to "mobile",
            listOf("casa", "home", "fisso") + ar("البيت", "المنزل") to "home",
            listOf("lavoro", "ufficio", "work", "office") + ar("الشغل", "العمل", "المكتب") to "work",
        )
        private val PauseWords = setOf(
            "pausa", "metti in pausa", "metti in pausa la musica", "ferma", "ferma la musica", "stoppa", "stoppa la musica",
            "stop", "pause", "stop the music", "stop music", "pause the music", "pause music",
        ) + ar("وقف", "وقف الموسيقى", "وقف الاغاني", "وقف الأغنية", "وقف المزيكا", "إيقاف", "إيقاف مؤقت", "اسكت", "استوب", "بس", "اقفل الأغاني", "اقفل المزيكا")
        private val ResumeWords = setOf("riprendi", "continua", "play", "riproduci", "resume", "metti la musica", "fai partire la musica") +
            ar("كمل", "كمل الاغاني", "كمل الأغنية", "استمر", "استكمل", "شغل", "شغل تاني", "رجع الأغاني", "تابع")
        private val NextWords = setOf(
            "avanti", "prossima", "prossimo", "prossima canzone", "prossimo brano", "brano successivo", "canzone successiva",
            "salta", "passa alla prossima", "passa alla prossima canzone", "passa al prossimo brano", "passa al brano successivo",
            "passa alla canzone successiva", "vai alla prossima", "vai al prossimo", "vai alla prossima canzone", "vai al prossimo brano",
            "vai al brano successivo", "vai alla canzone successiva", "next", "skip", "next song", "next track", "skip this song",
        ) + ar("التالي", "التالية", "اللي بعده", "اللي بعدها", "الأغنية التالية", "الأغنية اللي بعدها", "غير الأغنية", "غيرها", "بعده", "بعدها", "عدي الأغنية", "فوت الأغنية")
        private val PreviousWords = setOf(
            "indietro", "precedente", "canzone precedente", "brano precedente", "torna indietro", "passa alla precedente",
            "passa alla canzone precedente", "passa al brano precedente", "vai alla precedente", "vai alla canzone precedente",
            "vai al brano precedente", "torna alla canzone precedente", "previous", "back", "previous song", "previous track",
        ) + ar("السابق", "السابقة", "اللي قبله", "اللي قبلها", "الأغنية السابقة", "الأغنية اللي قبلها", "ارجع", "رجعها", "قبلها")
        private val ReadWords = setOf(
            "leggi i messaggi", "leggi messaggi", "leggi le notifiche", "leggi notifiche", "leggimi i messaggi", "read messages",
            "read my messages", "read the messages", "read me my messages", "read notifications", "any new messages",
        ) + ar(
            "اقرأ الرسائل", "اقرا الرسايل", "اقرالي الرسايل", "اقرالي الرسائل", "اقري الرسايل", "اقريلي الرسايل", "اقريلي الرسائل",
            "اقرأ الإشعارات", "اقرا الاشعارات", "فيه رسايل", "في رسايل جديدة", "فيه رسايل جديدة",
        )
        private val QuietOn = setOf("modalita silenziosa", "silenzia le notifiche", "silenzia notifiche", "non disturbare", "quiet mode", "do not disturb") +
            ar("الوضع الصامت", "صامت", "عدم الإزعاج", "ممنوع الإزعاج")
        private val QuietOff = setOf("disattiva modalita silenziosa", "disattiva il silenzioso", "riattiva le notifiche", "quiet mode off") +
            ar("الغي الوضع الصامت", "إلغاء الوضع الصامت", "اقفل الوضع الصامت", "شغل الإشعارات")
        /** Phrases that close the voice panel; not "stop", "بس" or "اسكت" alone: those pause the music. */
        private val Cancel = setOf(
            "annulla", "niente", "lascia stare", "lascia perdere",
            "cancel", "nothing", "never mind", "close", "close it", "shut up", "be quiet", "stop listening", "go away",
            "no thanks", "no thank you", "forget it", "forget about it",
        ) + ar(
            "إلغاء", "الغي", "خلاص", "ولا حاجة", "مش عايز", "مش عايزة", "مش عاوز", "مش عايز حاجة", "مش عايزة حاجة",
            "مش عاوز حاجة", "مفيش حاجة", "مافيش حاجة", "لا شيء", "انسى", "انسي", "سيبك", "اسكتي", "اسكتى", "اقفلي", "اقفل",
            "لا شكرا", "مش دلوقتي", "روحي",
        )
        /** Longest first: "close it" before "close". */
        private val CancelByLength = Cancel.sortedByDescending { it.length }
        /** Words that may pad a dismissal ("no, nothing", "بس خلاص") but don't close on their own. */
        private val DismissFillers = setOf("no", "it", "please", "thanks", "ok", "okay", "just", "now") +
            ar("لا", "بس", "يا", "شكرا", "طيب", "خالص", "دلوقتي")
        private val GenericMusic = setOf("musica", "la musica", "music", "some music") +
            ar("موسيقى", "الموسيقى", "مزيكا", "المزيكا", "اغاني", "الأغاني", "أغنية")

        /** The ways to ask to be driven somewhere (normalized: no accents, apostrophes as spaces). */
        private val NavigateVerbs = listOf(
            "portami", "accompagnami", "guidami", "conducimi", "naviga", "navigare", "avvia la navigazione", "avvia navigazione",
            "andiamo", "vai", "voglio andare", "devo andare", "dobbiamo andare", "vorrei andare", "fammi andare",
            "indicazioni stradali", "indicazioni", "percorso", "strada", "come arrivo", "come si arriva", "come ci arrivo",
            "imposta la destinazione", "imposta destinazione", "destinazione", "navigazione",
            "take me", "navigate", "drive me", "drive", "directions", "go", "get me", "route me", "route", "lets go", "let s go",
            "set destination", "set the destination", "how do i get",
        ) + ar(
            "خدني", "خذني", "وديني", "وصلني", "روحني", "روح", "نروح", "يلا بينا", "يلا", "عايز اروح", "عاوز اروح",
            "انا عايز اروح", "انا رايح", "رايحين", "عايزين نروح", "ودينا", "خدنا", "اوصل ازاي", "ازاي اروح", "أريد الذهاب", "اريد ان اذهب", "اذهب", "ابدأ الملاحة", "الملاحة", "اتجاهات", "الطريق",
        )

        /** What may come between the verb and the place ("al", "all'", "fino alla", "verso", "to"...). */
        private val NavigatePrepositions = listOf(
            "fino a", "fino al", "fino alla", "fino all", "fino allo", "verso il", "verso la", "verso",
            "a", "ad", "al", "allo", "alla", "all", "ai", "agli", "alle", "in", "nel", "nello", "nella", "nell",
            "da", "dal", "dallo", "dalla", "dall", "dai", "dagli", "dalle", "per", "su", "sul", "sulla", "presso",
            "to the", "to", "towards", "for", "at",
        ) + ar("إلى", "الي", "على", "عند", "ناحية", "في")

        /** The ways to ask for a stop on the way. */
        private val AddStopVerbs = listOf(
            "fermati", "fermiamoci", "facciamo tappa", "fai tappa", "fai una tappa", "facciamo una tappa", "fai una sosta",
            "facciamo una sosta", "aggiungi una tappa", "aggiungi tappa", "aggiungi una sosta", "aggiungi sosta",
            "tappa", "sosta",
            // Not "via": in Italian it is the street ("via Roma")
            "stop", "stop by", "add a stop", "add stop", "make a stop",
        ) + ar("اقف عند", "اقف", "وقفني عند", "نقف عند", "عدي على", "عدي ب", "عدينا على", "اعمل وقفة", "ضيف وقفة", "أضف محطة", "أضف وقفة") + listOf("passa", "passiamo").flatMap { verb ->
            // "Passa" alone is also "passa al prossimo brano": a stop only with "da" or "per"
            listOf("da", "dal", "dallo", "dalla", "dall", "dai", "dagli", "dalle", "per").map { "$verb $it" }
        }

        /** Words of the music: what they name is a song or a command, never a place to drive to. */
        private val MusicWords = setOf(
            "brano", "brani", "canzone", "canzoni", "traccia", "tracce", "prossima", "prossimo", "precedente",
            "successiva", "successivo", "musica", "radio", "volume", "playlist", "podcast", "album",
            "song", "songs", "track", "tracks", "music", "next", "previous",
        ) + ar("أغنية", "الأغنية", "اغاني", "الأغاني", "موسيقى", "الموسيقى", "مزيكا", "المزيكا", "التالي", "السابق", "راديو", "الراديو", "ألبوم")

        private val NavigateRegex = verbsThenPlace(NavigateVerbs)
        private val AddStopRegex = verbsThenPlace(AddStopVerbs)

        private fun verbsThenPlace(verbs: List<String>) = Regex(
            "^(" + verbs.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" +
                "(?: (?:" + NavigatePrepositions.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + "))? (.+)$",
        )
        private val SkipStopWords = setOf(
            "salta la tappa", "salta tappa", "salta la prossima tappa", "salta questa tappa", "salta la sosta",
            "skip the stop", "skip stop", "skip the next stop", "skip this stop",
        ) + ar("تخطى الوقفة", "اتخطى الوقفة", "فوت الوقفة", "الغي الوقفة", "تخطى المحطة")
        private val EndNavigationWords = setOf(
            "termina la navigazione", "termina navigazione", "ferma la navigazione", "interrompi la navigazione", "annulla la navigazione",
            "chiudi la navigazione", "stop navigation", "stop the navigation", "end navigation", "end the navigation", "cancel navigation",
            "cancel the navigation",
        ) + ar("إنهاء الملاحة", "انهي الملاحة", "الغي الملاحة", "إلغاء الملاحة", "وقف الملاحة", "اقفل الملاحة", "الغي المشوار", "خلاص وصلنا", "وقف الخريطة", "اقفل الخريطة")
        private val Digits = Regex("\\d+")
        private val ClimateWords = ar("تكييف", "التكييف", "المكيف", "التكيف", "مكيف") +
            ar("ac", "a c", "air conditioning", "air conditioner", "climate")
        private val FanWords = ar("المروحة", "مروحة", "الهوا", "الهواء", "التهوية") + ar("fan", "blower")
        private val TempWords = ar("الحرارة", "حرارة", "درجة الحرارة", "الحراره") + ar("temperature")
        private val SoundWords = ar("الصوت", "صوت") + ar("volume", "sound", "audio")
        private val TireWords = ar("الكاوتش", "كاوتش", "الكاوتشات", "العجل", "الاطارات", "اطارات") +
            ar("tire", "tires", "tyre", "tyres")
        private val TripWords = ar("مشاوير", "المشاوير", "مشاويري", "رحلات", "الرحلات") + ar("trips")
        private val UpWords = ar("علي", "ارفع", "زود", "زوّد", "علّي", "اعلى") + ar("up", "raise", "increase", "higher")
        private val DownWords = ar("وطي", "قلل", "نزل", "وطّي", "اخفض", "اقل") + ar("down", "lower", "decrease", "reduce")
        private val UpRoots = ar("علي", "رفع", "زود", "اعلي").map(::fold)
        private val DownRoots = ar("وطي", "قلل", "نزل", "خفض").map(::fold)
        private val OffRoots = ar("قفل", "طفي", "وقف").map(::fold)
        private val OffWords = ar("اقفل", "قفل", "طفي", "اطفي", "طفّي", "وقف") + ar("off", "stop")
        private val AutoWords = ar("اوتوماتيك", "أوتوماتيك", "تلقائي") + ar("auto", "automatic")
        private val MuteWords = ar("اكتم الصوت", "اكتم", "اقفل الصوت", "اسكت الصوت") + ar("mute")
        private val UnmuteWords = ar("رجع", "رجّع", "شغل", "افتح") + ar("unmute")
        private val Louder = ar("louder") + ar("علّي الصوت شوية")
        private val Quieter = ar("quieter", "softer")
        private val Warmer = ar("warmer", "hotter") + ar("دفيها", "دفي")
        private val Cooler = ar("cooler", "colder") + ar("بردها", "ساقعها", "برد", "ساقع")
        private val WarmerAlone = ar("warmer", "it s cold") + ar("دفيها", "دفي العربية", "انا بردان", "بردان")
        private val CoolerAlone = ar("cooler", "it s hot") + ar("بردها", "برد العربية", "ساقع العربية", "انا حران", "حران", "الجو حر")
        private val Articles = setOf("il", "lo", "la", "i", "gli", "le", "the")
        private val NotPlaces = setOf("avanti", "indietro", "back", "forward", "on", "ahead", "home screen") +
            ar("قدام", "ورا", "لورا")
        private val Sections = mapOf(
            setOf("home", "la home", "schermata principale") + ar("الرئيسية", "الشاشة الرئيسية") to Section.HOME,
            setOf("musica", "la musica", "music") + ar("الموسيقى", "الأغاني", "المزيكا") to Section.MUSIC,
            setOf("radio", "la radio") + ar("الراديو", "الإذاعة") to Section.RADIO,
            setOf("mappa", "la mappa", "mappe", "map", "maps") + ar("الخريطة", "الخرائط", "الخرايط") to Section.MAP,
            setOf("telefono", "il telefono", "rubrica", "phone", "contacts") + ar("التليفون", "الهاتف", "الموبايل", "جهات الاتصال", "الأسماء", "الأرقام") to Section.PHONE,
            setOf("veicolo", "il veicolo", "auto", "macchina", "la macchina", "vehicle", "car") + ar("العربية", "السيارة") to Section.VEHICLE,
            setOf("notifiche", "le notifiche", "notifications") + ar("الإشعارات") to Section.NOTIFICATIONS,
            setOf("impostazioni", "le impostazioni", "settings") + ar("الإعدادات") to Section.SETTINGS,
        )
    }
}
