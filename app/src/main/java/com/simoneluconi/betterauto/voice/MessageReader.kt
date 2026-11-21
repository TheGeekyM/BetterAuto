package com.simoneluconi.betterauto.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.simoneluconi.betterauto.BuildConfig
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.NavPrompts
import com.simoneluconi.betterauto.audio.Pcm
import com.simoneluconi.betterauto.notifications.CarNotification
import com.simoneluconi.betterauto.notifications.CarNotifications
import com.simoneluconi.betterauto.notifications.MessageText
import com.simoneluconi.betterauto.notifications.NotificationContent
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.ReadMessages
import com.simoneluconi.betterauto.voice.cloud.CloudSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New messages read aloud while a car screen is up: "Message from Ahmed: …" (the Gemini voice online, else the
 * phone's), then, when the chat can be answered, "Reply? Say it" and the microphone once ([VoiceAssistant.ask]): the
 * words go back through the chat's own reply action, "no" or silence sends nothing. Only the apps the driver picked,
 * never during a call, a turn of the assistant or a navigation prompt: a message waits for those to end. A code is
 * only announced. Everything on the main thread.
 */
object MessageReader {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var appContext: Context

    /** Messages older than this (the car came after them) are not read. */
    private var since = 0L
    /** Per chat, the newest message read (or replied to): it is not read again when the app posts the chat anew. */
    private val readUpTo = HashMap<String, Long>()
    /** Chats waiting to be read, by conversation, with the time they came. */
    private val waiting = LinkedHashMap<String, Pair<CarNotification, Long>>()
    /** Reading, or waiting for the reply to what was read (the wake listener keeps off meanwhile). */
    @Volatile var busy = false
        private set
    private val pumpLater = Runnable { pump() }

    class Reading(val text: String, val replyTo: CarNotification?)

    fun start(context: Context) {
        appContext = context.applicationContext
        since = System.currentTimeMillis() - START_GRACE_MS
        readUpTo.clear()
        job?.cancel()
        job = scope.launch { CarNotifications.arrived.collect(::arrived) }
    }

    fun stop() {
        job?.cancel()
        job = null
        waiting.clear()
        main.removeCallbacks(pumpLater)
        busy = false
    }

    private fun arrived(n: CarNotification) {
        val settings = LauncherSettings.get(appContext)
        if (settings.readMessages.value == ReadMessages.OFF || settings.quietMode.value) return
        if (n.packageName !in settings.readAloudApps.value) return
        if (CarNotifications.visible(listOf(n), settings).isEmpty()) return
        if (MessageText.unread(n.messages, after(n)).isEmpty()) return
        waiting.remove(n.conversationKey)
        waiting[n.conversationKey] = n to SystemClock.elapsedRealtime()
        pump()
    }

    private fun after(n: CarNotification) = maxOf(since, readUpTo[n.conversationKey] ?: 0L)

    /** Nothing else is speaking or listening: a call, the assistant, a navigation prompt, a sound of ours. */
    private fun free(): Boolean =
        Calls.current.value == null && VoiceAssistant.state.value is VoiceState.Idle && !CarVoice.speaking && !NavPrompts.speaking(appContext)

    private fun pump() {
        main.removeCallbacks(pumpLater)
        if (busy || waiting.isEmpty()) return
        if (!free()) {
            main.postDelayed(pumpLater, RETRY_MS)
            return
        }
        val (key, entry) = waiting.entries.first()
        waiting.remove(key)
        val (queued, at) = entry
        // The chat as it is now; gone (read on the phone, dismissed) or too old: nothing to say
        val n = CarNotifications.all.value.firstOrNull { it.key == queued.key }
        if (n == null || SystemClock.elapsedRealtime() - at > MAX_WAIT_MS) return pump()
        read(n)
    }

    private fun read(n: CarNotification) {
        val settings = LauncherSettings.get(appContext)
        val unread = MessageText.unread(n.messages, after(n))
        if (unread.isEmpty()) return pump()
        readUpTo[n.conversationKey] = unread.maxOf { it.time }
        val mode = settings.readMessages.value
        val spoken = MessageText.spoken(n, unread, mode == ReadMessages.ANNOUNCE || NotificationContent.hiddenNow(settings), words())
        val ask = mode == ReadMessages.READ_REPLY && spoken.canReply && n.reply != null && settings.voiceEnabled.value
        EventLog.log("Messages: reading ${unread.size} from ${n.packageName}${if (ask) ", then asking for a reply" else ""}")
        busy = true
        say(if (ask) "${spoken.text}. ${text(R.string.voice_reply_ask)}" else spoken.text) {
            if (ask) listen(n) else done()
        }
    }

    private fun done() {
        busy = false
        pump()
    }

    /** "Read my messages": the newest of each chat (three at most), and the chat to answer afterwards, if any. */
    fun reading(context: Context): Reading? {
        appContext = context.applicationContext
        val settings = LauncherSettings.get(appContext)
        val list = CarNotifications.visible(CarNotifications.all.value, settings).take(3)
        if (list.isEmpty()) return null
        val hidden = NotificationContent.hiddenNow(settings)
        val lines = list.map { MessageText.line(it, hidden, words()) }
        list.forEach { n -> n.messages.maxOfOrNull { it.time }?.let { readUpTo[n.conversationKey] = it } }
        val replyTo = list.first().takeIf {
            it.reply != null && lines.first().canReply && settings.readMessages.value == ReadMessages.READ_REPLY && settings.voiceEnabled.value
        }
        val said = lines.joinToString(". ") { it.text }
        return Reading(if (replyTo != null) "$said. ${text(R.string.voice_reply_ask)}" else said, replyTo)
    }

    /** Says [reading] (shown already by the assistant), then asks for the reply it offers. */
    fun speak(context: Context, reading: Reading) {
        appContext = context.applicationContext
        busy = true
        say(reading.text) { reading.replyTo?.let(::listen) ?: done() }
    }

    /** "رد على أحمد" with nothing dictated: what to say is asked for. */
    fun askReply(context: Context, n: CarNotification) {
        appContext = context.applicationContext
        busy = true
        say(text(R.string.voice_reply_ask_to, n.title)) { listen(n) }
    }

    /** The chat to answer: the newest that can be, or the one whose name (or a sender's) fits [contact] best. */
    fun target(context: Context, contact: String?): CarNotification? {
        val settings = LauncherSettings.get(context)
        val list = CarNotifications.visible(CarNotifications.all.value, settings).filter { it.reply != null }
        if (contact == null) return list.firstOrNull()
        val names = list.flatMap { n -> listOf(n.title) + n.messages.mapNotNull { it.sender } }.distinct()
        val best = CommandParser(names).matchContacts(CommandParser.normalize(contact)).firstOrNull() ?: return null
        return list.firstOrNull { it.title == best } ?: list.firstOrNull { n -> n.messages.any { it.sender == best } }
    }

    /** [text] through the chat's reply action; true when the app took it. */
    fun send(context: Context, n: CarNotification, text: String): Boolean {
        val ok = n.reply?.send(context.applicationContext, text) == true
        EventLog.log("Messages: reply of ${text.length} characters to ${n.packageName} ${if (ok) "sent" else "not sent"}")
        // The app posts the chat again with the reply in it: nothing up to now is read again
        if (ok) readUpTo[n.conversationKey] = System.currentTimeMillis()
        return ok
    }

    /** The microphone once, for the reply to [n]. */
    private fun listen(n: CarNotification) {
        // A call came, or the driver started talking to the assistant: no question now
        if (Calls.current.value != null || VoiceAssistant.isBusy) return done()
        val question = VoiceAssistant.Question(text(R.string.voice_reply_hint, n.title), REPLY_LISTEN_MS, yesNo = false) { words ->
            when {
                words == null -> Speech.cancelTone()
                CommandParser.cancels(words) -> VoiceAssistant.report(appContext, words, text(R.string.voice_reply_cancelled))
                send(appContext, n, words) -> VoiceAssistant.report(appContext, words, text(R.string.voice_reply_sent))
                else -> VoiceAssistant.report(appContext, words, text(R.string.voice_reply_failed), understood = false)
            }
            done()
        }
        VoiceAssistant.ask(appContext, question)
    }

    /**
     * Says [text] in the assistant's voice, then [then] (main thread) once it has been heard, or after a time it
     * can't take longer than. Online the Gemini voice (the persona's), else the recordings or the phone's voice.
     */
    internal fun say(text: String, then: () -> Unit) = say(appContext, text, then)

    internal fun say(context: Context, text: String, then: () -> Unit) {
        val ctx = context.applicationContext
        val once = AtomicBoolean(false)
        lateinit var limit: Runnable
        val go: () -> Unit = { if (once.compareAndSet(false, true)) main.post { main.removeCallbacks(limit); then() } }
        limit = Runnable {
            EventLog.log("Messages: the end of the speech was not heard, going on")
            go()
        }
        val settings = LauncherSettings.get(ctx)
        if (!VoiceAssistant.online(ctx)) {
            main.postDelayed(limit, speechMs(text) + ENGINE_MS)
            Speech.say(ctx, text, go)
            return
        }
        val arabic = text.any { it in '؀'..'ۿ' }
        val voice = settings.geminiVoice.value.apiName
        scope.launch(Dispatchers.IO) {
            val spoken = CloudSpeech.speak(BuildConfig.GEMINI_KEY, voice, text, arabic) {}
            main.post {
                if (!spoken) {
                    main.postDelayed(limit, speechMs(text) + ENGINE_MS)
                    Speech.say(ctx, text, go)
                    return@post
                }
                main.postDelayed(limit, speechMs(text))
                // Its end in the car: a moment of silence queued after it; on the phone only its last piece still plays
                val marked = CarVoice.play(AudioAttributes.USAGE_MEDIA, queued = true, done = go) { listOf(Pcm(FloatArray(480), 48_000, 1)) }
                if (!marked) main.postDelayed({ go() }, PHONE_TAIL_MS)
            }
        }
    }

    private fun speechMs(text: String) = (2_000L + text.length * 90L).coerceAtMost(60_000L)

    private fun words(): MessageText.Words = MessageText.Words(
        from = { text(R.string.voice_message_from, it) },
        inGroup = { who, group -> text(R.string.voice_message_in_group, who, group) },
        code = { text(R.string.voice_message_code, it) },
    )

    /** In the voice language, which may differ from the interface's. */
    private fun text(id: Int, vararg args: Any): String = AppLocales.voiceContext(appContext).getString(id, *args)

    /** Before the car session: a message that came while the phone was connecting is still read. */
    private const val START_GRACE_MS = 60_000L
    private const val RETRY_MS = 1_000L
    /** A message that waited this long (a long call) is not read any more: it is on the screen. */
    private const val MAX_WAIT_MS = 3 * 60_000L
    private const val REPLY_LISTEN_MS = 15_000L
    /** The phone's speech engine may take a moment to start. */
    private const val ENGINE_MS = 3_000L
    private const val PHONE_TAIL_MS = 600L
}
