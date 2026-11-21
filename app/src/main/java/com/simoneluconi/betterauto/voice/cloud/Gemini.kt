package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.EventLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gemini over plain HTTP (no Google SDK): the recorded command to text, and the online assistant's answers to
 * speech. One shared client, so the TLS connection [prewarm] opens while the driver talks is the one the request
 * uses. The key goes in a header, never in a URL or the log; errors carry the status and Google's message only.
 */
class Gemini(private val base: String = BASE, sttTimeoutMs: Long = 6_000, ttsTimeoutMs: Long = 15_000) {
    private val stt = Http.newBuilder().callTimeout(sttTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val tts = Http.newBuilder().callTimeout(ttsTimeoutMs, TimeUnit.MILLISECONDS).build()

    /** Opens the connection ahead of the request (a model listing, no cost); failures don't matter. */
    fun prewarm(key: String) {
        runCatching {
            Http.newCall(Request.Builder().url("$base/v1beta/models?pageSize=1").header("x-goog-api-key", key).get().build())
                .execute().close()
        }
    }

    /** [onCall] gets the request, so a transcription no longer needed can be cancelled (its upload stopped). */
    fun transcribe(key: String, model: String, wav: ByteArray, onCall: (okhttp3.Call) -> Unit = {}): String {
        val request = Request.Builder()
            .url("$base/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(transcriptionRequest(wav).toString().toRequestBody(JSON))
            .build()
        stt.newCall(request).also(onCall).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure("Gemini speech to text", response.code, body)
            return transcript(body)
        }
    }

    /**
     * [text] spoken in [voice], streamed: [onAudio] gets each piece of 16-bit PCM (and its rate) as it arrives, so
     * playback starts with the first. Returns how many pieces came.
     */
    fun speak(key: String, voice: String, text: String, arabic: Boolean, onAudio: (ShortArray, Int) -> Unit): Int {
        val body = speechRequest(voice, text, arabic).toString()
        if (streaming) {
            val pieces = runCatching { stream(key, body, onAudio) }.getOrElse { e ->
                // Out of credits is the same either way; anything else: the plain request may still work
                if (e.message == NO_CREDITS) throw e
                EventLog.log("Gemini voice: streaming failed (${e.message}), plain requests from now on")
                0
            }
            if (pieces > 0) return pieces
            streaming = false
        }
        val request = Request.Builder().url("$base/v1beta/models/$TTS_MODEL:generateContent")
            .header("x-goog-api-key", key).post(body.toRequestBody(JSON)).build()
        tts.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure("Gemini voice", response.code, text)
            return audio(text).onEach { (pcm, rate) -> onAudio(pcm, rate) }.size
        }
    }

    /** The streamed request (`alt=sse`): each event's audio as it arrives. */
    private fun stream(key: String, body: String, onAudio: (ShortArray, Int) -> Unit): Int {
        val request = Request.Builder()
            .url("$base/v1beta/models/$TTS_MODEL:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody(JSON))
            .build()
        tts.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failure("Gemini voice", response.code, response.body?.string().orEmpty())
            val source = response.body?.source() ?: throw IOException("Gemini voice: no body")
            var pieces = 0
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                audio(line.removePrefix("data:").trim()).forEach { (pcm, rate) ->
                    pieces++
                    onAudio(pcm, rate)
                }
            }
            return pieces
        }
    }

    /** Whether the streamed speech works on this model; once it fails, plain requests for this run. */
    @Volatile private var streaming = true

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com"
        const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private val JSON = "application/json".toMediaType()

        /** Shared: keep-alive connections to Google, reused by every call. */
        val Http: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

        private const val INSTRUCTION = "Transcribe this speech exactly as spoken. It is Egyptian Arabic, English, " +
            "Italian, or a mix. Output only the transcript, nothing else; output nothing if there is no speech."

        fun transcriptionRequest(wav: ByteArray): JSONObject {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                    .put("data", Base64.getEncoder().encodeToString(wav))))
                .put(JSONObject().put("text", INSTRUCTION))
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0))
        }

        /** The first candidate's text; no candidate is "" (nothing heard), a blocked prompt an error. */
        fun transcript(body: String): String {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                if (blocked.isNotEmpty()) throw IOException("Gemini speech to text: blocked ($blocked)")
                return ""
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
            return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        }

        fun speechRequest(voice: String, text: String, arabic: Boolean): JSONObject {
            val prompt = Persona.ttsStyle(arabic) + text
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        }

        /** The audio parts of one response (or stream event): 16-bit little-endian PCM, the rate from the mime type. */
        fun audio(body: String): List<Pair<ShortArray, Int>> {
            val parts = runCatching { JSONObject(body) }.getOrNull()?.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
            return (0 until parts.length()).mapNotNull { i ->
                val part = parts.getJSONObject(i)
                val data = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: return@mapNotNull null
                val mime = data.optString("mimeType", data.optString("mime_type"))
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: 24_000
                val bytes = Base64.getDecoder().decode(data.optString("data"))
                val samples = ShortArray(bytes.size / 2) { (bytes[2 * it].toInt() and 0xFF or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
                samples.takeIf { it.isNotEmpty() }?.let { it to rate }
            }
        }

        const val NO_CREDITS = "Gemini has no credits left: add billing in AI Studio"

        /** Out of credits said plainly; else the status and Google's message (which never quotes the key). */
        fun failure(what: String, code: Int, body: String): IOException {
            val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
            val status = error?.optString("status").orEmpty()
            if (code == 402 || code == 429 || status == "RESOURCE_EXHAUSTED") return IOException(NO_CREDITS)
            return IOException("$what: HTTP $code $status ${error?.optString("message").orEmpty().take(200)}".trim())
        }
    }
}
