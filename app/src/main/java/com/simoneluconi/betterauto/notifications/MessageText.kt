package com.simoneluconi.betterauto.notifications

/** One message of a chat notification's MessagingStyle, as the app gave it (before it becomes a [NotificationMessage]). */
data class StyleEntry(val sender: String?, val senderKey: String?, val text: String?, val time: Long)

/**
 * What the car says about a new message: who wrote ("Message from Ahmed", "Ahmed in Family"), what (unless it is
 * hidden or a verification code), and whether the driver may then reply by voice. Pure, so it is tested.
 */
object MessageText {
    /** The texts, in the voice language: "Message from %1$s", "%1$s in %2$s", "A code from %1$s". */
    class Words(val from: (String) -> String, val inGroup: (String, String) -> String, val code: (String) -> String)

    /** [text] to say; [canReply]: worth asking for a reply afterwards (not after a code or a bare announcement). */
    data class Spoken(val text: String, val canReply: Boolean)

    /** The apps read aloud until the driver picks others: WhatsApp, Telegram and the usual SMS apps. */
    val DefaultApps = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging",
    )

    /**
     * MessagingStyle messages as [NotificationMessage]s, the last [max]. A message with no sender, or with the
     * sender the style names as the phone's own user ([userKey] or [userName]), is the driver's own: kept on the
     * screen, never read aloud.
     */
    fun fromStyle(entries: List<StyleEntry>, userKey: String?, userName: String?, max: Int): List<NotificationMessage> =
        entries.takeLast(max).map { e ->
            val mine = e.sender == null || (userKey != null && e.senderKey == userKey) ||
                (e.senderKey == null && userName != null && e.sender == userName)
            NotificationMessage(e.sender, e.text.orEmpty(), e.time, fromMe = mine)
        }

    /** The messages not read aloud yet: newer than [since], not the driver's own, the last [max]. */
    fun unread(messages: List<NotificationMessage>, since: Long, max: Int = MAX_READ): List<NotificationMessage> =
        messages.filter { !it.fromMe && it.text.isNotBlank() && it.time > since }.takeLast(max)

    /** Who wrote [m] in [n]: the chat's name, or in a group "<sender> in <group>". */
    fun who(n: CarNotification, m: NotificationMessage?, words: Words): String {
        val sender = m?.sender?.takeIf { it.isNotBlank() }
        return if (n.isGroup && sender != null && sender != n.title) words.inGroup(sender, n.title) else sender ?: n.title
    }

    /**
     * What to say for [unread] messages of [n]. [announceOnly]: only who wrote (the driver's choice, or someone else
     * aboard); a verification code is never read out, only who sent it.
     */
    fun spoken(n: CarNotification, unread: List<NotificationMessage>, announceOnly: Boolean, words: Words): Spoken {
        val last = unread.lastOrNull() ?: n.last
        if (unread.any { looksLikeCode(it.text) }) return Spoken(words.code(n.title), canReply = false)
        val header = words.from(who(n, last, words))
        if (announceOnly || unread.isEmpty()) return Spoken(header, canReply = !announceOnly)
        // In a group with several people talking, each line says who
        val senders = unread.mapNotNull { it.sender }.toSet()
        val body = unread.joinToString(". ") { m ->
            val line = m.text.trim().trimEnd('.', '!', '?', '؟')
            if (n.isGroup && senders.size > 1 && m !== last && m.sender != null) "${m.sender}: $line" else line
        }
        return Spoken("$header: $body", canReply = true)
    }

    /**
     * One line of "read my messages" for [n]: its newest message as [spoken] says it when it is a chat, or
     * "<title>: <text>" for any other alert (a reminder is not a "message from").
     */
    fun line(n: CarNotification, announceOnly: Boolean, words: Words): Spoken {
        val last = n.messages.lastOrNull { !it.fromMe && it.text.isNotBlank() }
        if (n.reply != null || last?.sender != null || last == null) return spoken(n, listOfNotNull(last), announceOnly, words)
        if (looksLikeCode(last.text)) return Spoken(words.code(n.title), canReply = false)
        return Spoken(if (announceOnly) n.title else "${n.title}: ${last.text.trim()}", canReply = false)
    }

    /**
     * A one-time code ("Your code is 482 913", "G-582014 is your Google verification code", "رمز التحقق ٤٨٢٩١٣"):
     * 4 to 8 digits (spaces or dashes inside allowed) with a word saying it is a code, or a message that is only that
     * number.
     */
    fun looksLikeCode(text: String): Boolean {
        val t = text.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("").lowercase()
        val numbers = CodeNumber.findAll(t).map { it.value.filter(Char::isDigit) }.filter { it.length in 4..8 }.toList()
        if (numbers.isEmpty()) return false
        if (t.trim().filter(Char::isLetterOrDigit).all(Char::isDigit)) return true
        return CodeWords.any { it in t }
    }

    private val CodeNumber = Regex("(?<![\\d])\\d(?:[ \\-]?\\d){3,7}(?![\\d])")
    private val CodeWords = listOf(
        "code", "otp", "verification", "verify", "passcode", "password", "pin ", "one-time", "2fa",
        "codice", "verifica", "accesso", "conferma",
        "رمز", "كود", "التحقق", "تحقق", "كلمة المرور", "كلمة السر", "التفعيل", "تفعيل",
    )

    private const val MAX_READ = 3
}
