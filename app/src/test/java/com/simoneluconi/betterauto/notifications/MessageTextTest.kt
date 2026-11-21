package com.simoneluconi.betterauto.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {
    private val words = MessageText.Words(
        from = { "Message from $it" },
        inGroup = { who, group -> "$who in $group" },
        code = { "A code from $it" },
    )

    private fun chat(title: String, group: Boolean, vararg messages: NotificationMessage, reply: Boolean = true) = CarNotification(
        key = "k", packageName = "com.whatsapp", appLabel = "WhatsApp", appIcon = null, conversationKey = "com.whatsapp|$title",
        title = title, isGroup = group, messages = messages.toList(), time = messages.lastOrNull()?.time ?: 0L, avatar = null,
        reply = if (reply) MessageReply { _, _ -> true } else null,
    )

    private fun m(sender: String?, text: String, time: Long, mine: Boolean = false) = NotificationMessage(sender, text, time, mine)

    @Test
    fun `the driver's own messages are told apart`() {
        val got = MessageText.fromStyle(
            listOf(
                StyleEntry("Ahmed", "p:ahmed", "إزيك؟", 1),
                StyleEntry(null, null, "تمام", 2),
                StyleEntry("Me", "p:me", "وانت؟", 3),
                StyleEntry("Sam", null, "hi", 4),
            ),
            userKey = "p:me", userName = "Sam", max = 12,
        )
        assertEquals(listOf(false, true, true, true), got.map { it.fromMe })
        assertEquals("إزيك؟", got.first().text)
        assertEquals(2, MessageText.fromStyle(List(5) { StyleEntry("A", null, "$it", it.toLong()) }, null, null, max = 2).size)
    }

    @Test
    fun `only messages not read yet, not the driver's, the last three`() {
        val list = listOf(m("A", "old", 10), m("A", "one", 20), m(null, "mine", 25, mine = true), m("A", " ", 26),
            m("A", "two", 30), m("A", "three", 40), m("A", "four", 50))
        assertEquals(listOf("two", "three", "four"), MessageText.unread(list, since = 15).map { it.text })
        assertEquals(listOf("four"), MessageText.unread(list, since = 40).map { it.text })
        assertTrue(MessageText.unread(list, since = 50).isEmpty())
    }

    @Test
    fun `a chat is read with who wrote, a group with its name`() {
        val ahmed = chat("Ahmed", false, m("Ahmed", "انا جاي", 1))
        assertEquals(MessageText.Spoken("Message from Ahmed: انا جاي", canReply = true), MessageText.spoken(ahmed, ahmed.messages, false, words))
        val family = chat("Family", true, m("Sara", "Dinner at 8!", 1))
        assertEquals("Message from Sara in Family: Dinner at 8", MessageText.spoken(family, family.messages, false, words).text)
        // Several people: each line but the last says who
        val two = chat("Family", true, m("Sara", "Dinner at 8", 1), m("Omar", "OK", 2))
        assertEquals("Message from Omar in Family: Sara: Dinner at 8. OK", MessageText.spoken(two, two.messages, false, words).text)
    }

    @Test
    fun `announce only says who, a code is never read`() {
        val ahmed = chat("Ahmed", false, m("Ahmed", "secret", 1))
        assertEquals(MessageText.Spoken("Message from Ahmed", canReply = false), MessageText.spoken(ahmed, ahmed.messages, true, words))
        val bank = chat("Bank", false, m("Bank", "Your verification code is 482 913", 1))
        assertEquals(MessageText.Spoken("A code from Bank", canReply = false), MessageText.spoken(bank, bank.messages, false, words))
    }

    @Test
    fun `codes are recognized`() {
        listOf(
            "Your verification code is 482913", "G-582014 is your Google verification code", "Il tuo codice è 4821",
            "رمز التحقق الخاص بك ٤٨٢٩١٣", "كود التفعيل 5521", "123456", "OTP: 7781-22", "Use 3391 to log in. Don't share this code.",
        ).forEach { assertTrue(it, MessageText.looksLikeCode(it)) }
        listOf(
            "I'll be there at 8", "call me on 0100 123 4567 please", "حجزت ٣ تذاكر", "The meeting is in room 1204",
            "ok", "code review tomorrow?", "سعرها 2500 جنيه",
        ).forEach { assertFalse(it, MessageText.looksLikeCode(it)) }
    }

    @Test
    fun `read my messages tells chats from other alerts`() {
        val reminder = chat("Dentist", false, m(null, "Tomorrow 9:00", 1), reply = false)
        assertEquals(MessageText.Spoken("Dentist: Tomorrow 9:00", canReply = false), MessageText.line(reminder, false, words))
        val ahmed = chat("Ahmed", false, m("Ahmed", "where are you", 1), m(null, "coming", 2, mine = true))
        assertEquals("Message from Ahmed: where are you", MessageText.line(ahmed, false, words).text)
    }
}
