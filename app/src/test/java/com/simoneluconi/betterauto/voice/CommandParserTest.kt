package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.settings.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser(listOf("Mario Rossi", "Mario Bianchi", "Giulia Bianchi", "Officina Ferrari", "Mamma"))

    @Test
    fun calls() {
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null), parser.parse("Chiama Mario Rossi"))
        assertEquals(VoiceCommand.Call(listOf("Giulia Bianchi"), "mobile"), parser.parse("chiama Giulia Bianchi sul cellulare"))
        assertEquals(VoiceCommand.Call(listOf("Mamma"), "home"), parser.parse("telefona a mamma casa"))
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), "work"), parser.parse("call Mario Rossi at work"))
    }

    @Test
    fun slipsAreFoundButMarkedAsGuesses() {
        // Found, but not exact: the driver must confirm before anything is dialed
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null, exact = false), parser.parse("chiama Mario Rosi"))
        assertEquals(VoiceCommand.Call(listOf("Officina Ferrari"), null, exact = false), parser.parse("chiama officina ferari"))
    }

    @Test
    fun aWrongOrMissingSurnameIsNoMatch() {
        val contacts = CommandParser(listOf("Mauro Carboni"))
        // "Mario Rossi" is not "Mauro Carboni": the surname rules it out
        assertNull(contacts.parse("chiama Mario Rossi"))
        // "Mario" alone, close to "Mauro" but saying nothing of the surname, is not enough either
        assertNull(contacts.parse("chiama Mario"))
    }

    @Test
    fun ambiguousNamesAreAllOffered() {
        val call = parser.parse("chiama Mario") as VoiceCommand.Call
        assertEquals(setOf("Mario Rossi", "Mario Bianchi"), call.candidates.toSet())
    }

    @Test
    fun unknownNamesAreNotGuessed() {
        assertNull(parser.parse("chiama Federico Verdi"))
        assertNull(parser.parse("chiama"))
    }

    @Test
    fun media() {
        assertEquals(VoiceCommand.Pause, parser.parse("Metti in pausa la musica"))
        assertEquals(VoiceCommand.Next, parser.parse("prossima canzone"))
        assertEquals(VoiceCommand.Previous, parser.parse("indietro"))
        assertEquals(VoiceCommand.Resume, parser.parse("metti la musica"))
        assertEquals(VoiceCommand.Play("vasco rossi"), parser.parse("metti Vasco Rossi"))
        assertEquals(VoiceCommand.Play("coldplay"), parser.parse("play Coldplay"))
    }

    @Test
    fun sectionsAndModes() {
        assertEquals(VoiceCommand.Open(Section.VEHICLE), parser.parse("apri veicolo"))
        assertEquals(VoiceCommand.Open(Section.MUSIC), parser.parse("musica"))
        assertEquals(VoiceCommand.Quiet(true), parser.parse("modalità silenziosa"))
        assertEquals(VoiceCommand.ReadNotifications, parser.parse("leggi i messaggi"))
        assertEquals(VoiceCommand.Cancel, parser.parse("lascia stare"))
    }

    @Test
    fun missesAreExplained() {
        assertEquals(CommandParser.Miss.ContactNotFound("federico verdi"), parser.miss("chiama Federico Verdi"))
        assertEquals(CommandParser.Miss.UnknownPlace("garage"), parser.miss("apri garage"))
        assertNull(parser.miss("che tempo fa"))
    }

    @Test
    fun otherTextIsNoCommand() {
        assertNull(parser.parse("che tempo fa domani"))
        assertNull(parser.parse(""))
    }

    @Test
    fun arabic() {
        val arabic = CommandParser(listOf("أحمد علي", "Mario Rossi"))
        // "بأحمد": the "by" sticks to the name; hamza is dropped on both sides
        assertEquals(VoiceCommand.Call(listOf("أحمد علي"), null, exact = true), arabic.parse("اتصل بأحمد علي"))
        assertEquals(VoiceCommand.Call(listOf("أحمد علي"), "mobile"), arabic.parse("كلم احمد علي على الموبايل"))
        assertEquals(VoiceCommand.Navigate("المطار"), arabic.parse("خدني للمطار"))
        assertEquals(VoiceCommand.Navigate("مدينة نصر"), arabic.parse("وديني إلى مدينة نصر"))
        assertEquals(VoiceCommand.Play("عمرو دياب"), arabic.parse("شغل عمرو دياب"))
        assertEquals(VoiceCommand.Resume, arabic.parse("شغل الأغاني"))
        assertEquals(VoiceCommand.Pause, arabic.parse("وقف"))
        assertEquals(VoiceCommand.Next, arabic.parse("الأغنية اللي بعدها"))
        assertEquals(VoiceCommand.Open(Section.MAP), arabic.parse("افتح الخريطة"))
        assertEquals(VoiceCommand.EndNavigation, arabic.parse("الغي الملاحة"))
        assertEquals(VoiceCommand.Cancel, arabic.parse("إلغاء"))
        // Egyptian
        assertEquals(VoiceCommand.Navigate("التجمع الخامس"), arabic.parse("عايز اروح التجمع الخامس"))
        assertEquals(VoiceCommand.Navigate("البيت"), arabic.parse("وديني على البيت"))
        assertEquals(VoiceCommand.Play("ويجز"), arabic.parse("سمعني ويجز"))
        assertEquals(VoiceCommand.Next, arabic.parse("غيرها"))
        assertEquals(VoiceCommand.Call(listOf("أحمد علي"), null), arabic.parse("كلملي أحمد علي"))
    }

    @Test
    fun carControls() {
        assertEquals(VoiceCommand.AcPower(true), parser.parse("شغّل التكييف"))
        assertEquals(VoiceCommand.AcPower(false), parser.parse("اقفل التكييف"))
        assertEquals(VoiceCommand.Open(Section.MAP), parser.parse("افتحي الخريطه"))
        assertEquals(VoiceCommand.Open(Section.RADIO), parser.parse("افتحي الراديو"))
        assertEquals(VoiceCommand.Open(Section.RADIO), parser.parse("ممكن تفتحيلي الراديو لو سمحتي"))
        assertEquals(VoiceCommand.AcPower(true), parser.parse("ممكن تفتحى التكييف"))
        assertEquals(VoiceCommand.AcPower(true), parser.parse("افتحي التكييف لو سمحتي"))
        assertEquals(VoiceCommand.AcPower(false), parser.parse("اقفليلي التكييف"))
        assertEquals(VoiceCommand.AcPower(false), parser.parse("ممكن تطفي المكيف"))
        assertEquals(VoiceCommand.Volume("up"), parser.parse("زودي الصوت شوية"))
        assertEquals(VoiceCommand.AcTemp(delta = -1), parser.parse("وطيلي الحراره"))
        assertEquals(VoiceCommand.AcTemp(value = 22), parser.parse("خلي الحرارة ٢٢"))
        assertEquals(VoiceCommand.AcTemp(delta = -1), parser.parse("وطي الحرارة"))
        assertEquals(VoiceCommand.AcTemp(delta = -1), parser.parse("انا حران"))
        assertEquals(VoiceCommand.AcAuto, parser.parse("خلي التكييف أوتوماتيك"))
        assertEquals(VoiceCommand.Fan(1), parser.parse("علّي المروحة"))
        assertEquals(VoiceCommand.Volume("up"), parser.parse("علّي الصوت"))
        assertEquals(VoiceCommand.Volume("mute"), parser.parse("اكتم الصوت"))
        assertEquals(VoiceCommand.Tires, parser.parse("الكاوتش تمام؟"))
        assertEquals(VoiceCommand.Trips, parser.parse("إيه آخر مشاويري"))
        assertEquals(VoiceCommand.AcPower(true), parser.parse("turn on the AC"))
        assertEquals(VoiceCommand.Volume("down"), parser.parse("turn the volume down"))
        assertEquals(VoiceCommand.Tires, parser.parse("are the tyres ok"))
        // Still music and places
        assertEquals(VoiceCommand.Play("عمرو دياب"), parser.parse("شغل عمرو دياب"))
    }

    @Test
    fun dismissals() {
        listOf(
            "nothing", "Nothing, close.", "no nothing", "close it", "Shut up!", "be quiet", "never mind", "cancel",
            "stop listening", "go away", "no thanks", "No, thank you", "forget it", "nothing close please",
        ).forEach { assertEquals(it, VoiceCommand.Cancel, parser.parse(it)) }
        listOf(
            "خلاص", "ولا حاجة", "مش عايز حاجة", "مش عايزة حاجة", "مفيش حاجة", "اسكتي", "اسكتى", "بس خلاص", "اقفلي",
            "اقفل", "سيبك", "انسي", "انسى", "لا، شكراً", "مش دلوقتي", "روحي", "خلاص مش عايز حاجة اقفلي", "لا ولا حاجة",
        ).forEach { assertEquals(it, VoiceCommand.Cancel, parser.parse(it)) }
    }

    @Test
    fun dismissWordsKeepTheirOtherMeanings() {
        assertEquals(VoiceCommand.Pause, parser.parse("stop"))
        assertEquals(VoiceCommand.Pause, parser.parse("stop the music"))
        assertEquals(VoiceCommand.Pause, parser.parse("اسكت"))
        assertEquals(VoiceCommand.Pause, parser.parse("بس"))
        assertEquals(VoiceCommand.Pause, parser.parse("اقفل الأغاني"))
        assertEquals(VoiceCommand.AcPower(false), parser.parse("اقفل التكييف"))
        assertEquals(VoiceCommand.Volume("mute"), parser.parse("اقفل الصوت"))
        assertEquals(VoiceCommand.EndNavigation, parser.parse("اقفل الخريطة"))
        assertEquals(VoiceCommand.EndNavigation, parser.parse("خلاص وصلنا"))
        assertEquals(VoiceCommand.EndNavigation, parser.parse("cancel navigation"))
        assertEquals(VoiceCommand.Quiet(false), parser.parse("اقفل الوضع الصامت"))
        assertEquals(VoiceCommand.Quiet(true), parser.parse("quiet mode"))
        // Fillers alone close nothing
        assertNull(parser.parse("no"))
        assertNull(parser.parse("thanks"))
    }
}
