package com.simoneluconi.betterauto.voice.cloud

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Gemini against a local fake server: no real API, no real key. */
class GeminiTest {
    private val server = MockWebServer().apply { start() }
    private val gemini = Gemini(server.url("/").toString().trimEnd('/'))

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Test
    fun `transcription request and answer`() {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":" شغّل "},{"text":"التكييف\n"}]}}]}"""))
        val wav = byteArrayOf(1, 2, 3)
        assertEquals("شغّل التكييف", gemini.transcribe("fake-key", "gemini-3.5-flash-lite", wav))
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", request.path)
        assertEquals("fake-key", request.getHeader("x-goog-api-key"))
        val body = JSONObject(request.body.readUtf8())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("audio/wav", parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type"))
        assertArrayEquals(wav, Base64.getDecoder().decode(parts.getJSONObject(0).getJSONObject("inline_data").getString("data")))
        assertTrue(parts.getJSONObject(1).getString("text").startsWith("Transcribe this speech exactly as spoken."))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
    }

    @Test
    fun `errors say why without the key, out of credits plainly`() {
        server.enqueue(json("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""", 400))
        try {
            gemini.transcribe("fake-key", "m", ByteArray(2))
            fail()
        } catch (e: IOException) {
            assertEquals("Gemini speech to text: HTTP 400 INVALID_ARGUMENT API key not valid.", e.message)
            assertFalse(e.message!!.contains("fake-key"))
        }
        server.enqueue(json("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}""", 429))
        try {
            gemini.transcribe("k", "m", ByteArray(2))
            fail()
        } catch (e: IOException) {
            assertEquals(Gemini.NO_CREDITS, e.message)
        }
        assertEquals("", Gemini.transcript("""{"candidates":[]}"""))
    }

    @Test
    fun `speech streams piece by piece`() {
        fun piece(vararg s: Int) = Base64.getEncoder().encodeToString(ByteArray(s.size * 2) { i -> (s[i / 2] shr (8 * (i % 2))).toByte() })
        val sse = listOf(piece(1, 2), piece(3)).joinToString("") {
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/L16;codec=pcm;rate=24000\",\"data\":\"$it\"}}]}}]}\r\n\r\n"
        }
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse))
        val got = ArrayList<Pair<List<Short>, Int>>()
        val pieces = gemini.speak("fake-key", "Kore", "تمام", arabic = true) { pcm, rate -> got += pcm.toList() to rate }
        assertEquals(2, pieces)
        assertEquals(listOf(listOf<Short>(1, 2) to 24_000, listOf<Short>(3) to 24_000), got)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:streamGenerateContent?alt=sse", request.path)
        assertEquals("fake-key", request.getHeader("x-goog-api-key"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(Persona.ttsStyle(arabic = true) + "تمام",
            body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", config.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
    }

    @Test
    fun `a model that doesn't stream speech gets plain requests`() {
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(5, 0, 6, 0))
        server.enqueue(json("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"streaming not supported"}}""", 400))
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"$pcm"}}]}}]}"""))
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"$pcm"}}]}}]}"""))
        val got = ArrayList<List<Short>>()
        assertEquals(1, gemini.speak("k", "Kore", "hi", arabic = false) { s, _ -> got += s.toList() })
        assertEquals(listOf(listOf<Short>(5, 6)), got)
        assertTrue(server.takeRequest().path!!.contains(":streamGenerateContent?alt=sse"))
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", server.takeRequest().path)
        // Remembered: no second try at streaming
        assertEquals(1, gemini.speak("k", "Kore", "hi", arabic = false) { _, _ -> })
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", server.takeRequest().path)
    }
}
