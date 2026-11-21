package com.simoneluconi.betterauto.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.phone.CallInfo
import com.simoneluconi.betterauto.phone.CallPhase
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.voice.cloud.CloudSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An incoming call said by name while a car screen is up ("أحمد بيتصل، ترد؟", in the assistant's voice), then a few
 * seconds to answer by voice: "أيوه"/"رد"/"answer" picks up, "لا"/"اقفل"/"decline" rejects, anything else leaves it
 * ringing (the call screen's buttons still work). Through [Calls], i.e. the in-call service. Main thread.
 */
object CallAnnouncer {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var appContext: Context

    /** The ringing call already announced (or about to be). */
    private var announced: Int? = null
    /** The call whose answer the assistant is listening for. */
    private var asking: Int? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        job?.cancel()
        job = scope.launch { Calls.current.collect(::changed) }
    }

    fun stop() {
        job?.cancel()
        job = null
        main.removeCallbacks(announce)
        announced = null
        asking = null
    }

    private fun changed(call: CallInfo?) {
        if (call?.phase != CallPhase.RINGING) {
            main.removeCallbacks(announce)
            announced = null
            // Answered with a button or the wheel, or the caller gave up: the question goes with it
            if (asking != null) {
                asking = null
                if (VoiceAssistant.asking) VoiceAssistant.cancel()
            }
            return
        }
        if (call.id == announced || !LauncherSettings.get(appContext).announceCalls.value) return
        announced = call.id
        // The contact's name is looked up as the call comes in: a moment for it
        main.removeCallbacks(announce)
        main.postDelayed(announce, NAME_WAIT_MS)
    }

    private fun ringing(id: Int) = Calls.current.value?.takeIf { it.phase == CallPhase.RINGING && it.id == id }

    private val announce = Runnable {
        val id = announced ?: return@Runnable
        val call = ringing(id) ?: return@Runnable
        // The call goes first: a message being read or a turn of the assistant gives way
        if (VoiceAssistant.isBusy) VoiceAssistant.cancel()
        Speech.stop()
        CloudSpeech.stop()
        val name = call.name?.takeIf { it.isNotBlank() }
        EventLog.log("Calls: announcing the caller${if (name == null) " (no name)" else ""}")
        val said = name?.let { text(R.string.voice_call_incoming, it) } ?: text(R.string.voice_call_unknown)
        MessageReader.say(appContext, said) {
            if (ringing(id) == null || VoiceAssistant.isBusy) return@say
            asking = id
            VoiceAssistant.ask(appContext, VoiceAssistant.Question(text(R.string.voice_call_hint), LISTEN_MS, yesNo = true) { words ->
                if (asking == id) asking = null
                val yes = words?.let(CommandParser::yesNo)
                if (ringing(id) == null) return@Question
                EventLog.log("Calls: voice answer ${when (yes) { true -> "yes"; false -> "no"; null -> "none" }}")
                when (yes) {
                    true -> Calls.answer()
                    false -> Calls.end()
                    null -> Unit
                }
            })
        }
    }

    private fun text(id: Int, vararg args: Any): String = AppLocales.voiceContext(appContext).getString(id, *args)

    private const val NAME_WAIT_MS = 800L
    /** How long the answer is listened for; the phone keeps ringing after. */
    private const val LISTEN_MS = 6_000L
}
