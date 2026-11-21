package com.simoneluconi.betterauto.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies to messages by voice, and the yes or no the assistant's own questions wait for. */
class ReplyCommandTest {
    private val parser = CommandParser(listOf("أحمد علي", "ماما", "Mario Rossi", "Giulia Bianchi"))
    private fun reply(contact: String?, text: String) = VoiceCommand.Reply(contact, text)

    @Test
    fun `arabic replies`() {
        assertEquals(reply("احمد", "أنا جاي في الطريق"), parser.parse("رد على أحمد قوله أنا جاي في الطريق"))
        assertEquals(reply("احمد", "اتأخرت شوية"), parser.parse("ابعت لأحمد إني اتأخرت شوية"))
        assertEquals(reply("ماما", "جاي على الغدا"), parser.parse("ابعت رسالة لماما قوليلها جاي على الغدا"))
        // The name found among the contacts, the rest is the text
        assertEquals(reply("ماما", "خمس دقايق وأكون عندك"), parser.parse("رد على ماما خمس دقايق وأكون عندك"))
        assertEquals(reply(null, "حاضر"), parser.parse("رد عليه قوله حاضر"))
        assertEquals(reply(null, "مش فاضي دلوقتي"), parser.parse("ممكن ترد عليها مش فاضي دلوقتي"))
        // What is dictated is not run: "شغّل التكييف" goes in the message
        assertEquals(reply("احمد", "شغّل التكييف قبل ما أوصل"), parser.parse("رد على أحمد قوله شغّل التكييف قبل ما أوصل"))
        // Only a name: the assistant will ask what to say
        assertEquals(reply("احمد", ""), parser.parse("رد على أحمد"))
        assertEquals(reply(null, ""), parser.parse("رد على الرسالة"))
    }

    @Test
    fun `english and italian replies keep the words as said`() {
        assertEquals(reply("mario", "I'm running late, sorry!"), parser.parse("Reply to Mario saying I'm running late, sorry!"))
        assertEquals(reply("mario rossi", "Ten minutes"), parser.parse("reply to Mario Rossi Ten minutes"))
        assertEquals(reply(null, "on my way"), parser.parse("reply on my way"))
        assertEquals(reply(null, "OK"), parser.parse("reply to it saying OK"))
        assertEquals(reply("giulia", "arrivo tra poco, perché c'è traffico"), parser.parse("rispondi a Giulia che arrivo tra poco, perché c'è traffico"))
        assertEquals(reply(null, "va bene"), parser.parse("rispondi al messaggio che va bene"))
    }

    @Test
    fun `other sentences stay what they were`() {
        assertEquals(VoiceCommand.Call(listOf("Mario Rossi"), null), parser.parse("chiama Mario Rossi"))
        assertEquals(VoiceCommand.ReadNotifications, parser.parse("اقرالي الرسايل"))
        assertEquals(VoiceCommand.ReadNotifications, parser.parse("read my messages"))
        assertEquals(VoiceCommand.AcPower(true), parser.parse("شغل التكييف"))
        // "ابعت" not to someone, "رد" followed by anything: not a reply
        assertNull(parser.parse("ابعت موقعي"))
        assertNull(parser.parse("رد الفعل كان غريب"))
    }

    @Test
    fun `yes and no`() {
        listOf("أيوه", "ايوه رد", "رد", "آه", "yes", "answer it", "pick up", "sì rispondi", "تمام").forEach { assertEquals(it, true, CommandParser.yesNo(it)) }
        listOf("لا", "لأ اقفل", "اقفلي", "مش دلوقتي", "no", "decline", "hang up", "rifiuta", "متردش").forEach { assertEquals(it, false, CommandParser.yesNo(it)) }
        listOf("", "مين ده", "what was that", "I think we should go to the station first and then").forEach { assertNull(it, CommandParser.yesNo(it)) }
    }

    @Test
    fun `a reply that only says no is not sent`() {
        listOf("لا", "لا لا", "no", "cancel", "خلاص", "مش عايز ارد", "never mind", "lascia stare", "متبعتش").forEach { assertTrue(it, CommandParser.cancels(it)) }
        listOf("لا مش هقدر آجي النهارده", "no problem see you", "خلاص جاي", "أنا جاي").forEach { assertFalse(it, CommandParser.cancels(it)) }
    }
}
