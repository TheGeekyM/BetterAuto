package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.voice.VoiceCommand
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The real SDK, streamed, against a local fake server: what goes on the wire and how the stream is read. */
class ClaudeAgentTest {
    private val server = MockWebServer().apply { start() }
    private val agent = ClaudeAgent(server.url("/").toString().trimEnd('/'))
    private val call = { name: String, type: String? -> VoiceCommand.Call(listOf(name), type) }

    @After
    fun stop() {
        agent.close()
        server.shutdown()
    }

    private fun sse(vararg events: String) = MockResponse().setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { e -> "event: ${JSONObject(e).getString("type")}\ndata: $e\n\n" })

    private val start = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":900,"output_tokens":1}}}"""
    private fun end(reason: String) = arrayOf(
        """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},"usage":{"output_tokens":20}}""",
        """{"type":"message_stop"}""",
    )

    @Test
    fun `the tool call is acted on from the stream, and the request is shaped for speed`() {
        server.enqueue(sse(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"set_temperature","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"val"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ue\": 40}"}}""",
            """{"type":"content_block_stop","index":0}""", *end("tool_use")))
        var firstToken = 0
        val result = agent.decide("test-key", "claude-sonnet-5-5", "خلي الحرارة ٤٠", listOf("Sam Doe"),
            listOf(Conversation.Turn("hi", "Hello Sam")), call) { firstToken++ }
        assertEquals(CloudResult.Command(VoiceCommand.AcTemp(value = 32)), result)
        assertEquals(1, firstToken)

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertTrue(request.getHeader("Authorization")!!.endsWith("test-key"))
        assertTrue(request.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertEquals("default", body.getString("fallbacks"))
        assertFalse(body.has("thinking"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("auto", body.getJSONObject("tool_choice").getString("type"))
        assertTrue(body.getJSONObject("tool_choice").getBoolean("disable_parallel_tool_use"))
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        assertTrue(system.getString("text").contains("Sam Doe"))
        assertEquals(3, body.getJSONArray("messages").length())
        val tools = (0 until body.getJSONArray("tools").length()).map { body.getJSONArray("tools").getJSONObject(it) }
        assertEquals(21, tools.size)
        assertEquals("web_search_20260209", tools.single { it.optString("type").startsWith("web_search") }.getString("type"))
        tools.filter { it.has("input_schema") }.forEach {
            assertTrue(it.getBoolean("strict"))
            assertFalse(it.getJSONObject("input_schema").getBoolean("additionalProperties"))
        }
    }

    @Test
    fun `haiku has no thinking, no fallback and the older web search`() {
        server.enqueue(sse(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"media","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"action\":\"next\"}"}}""",
            """{"type":"content_block_stop","index":0}""", *end("tool_use")))
        assertEquals(CloudResult.Command(VoiceCommand.Next), agent.decide("k", "claude-haiku-5-5", "next", emptyList(), emptyList(), call))
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("fallbacks"))
        assertFalse(request.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
        val tools = body.getJSONArray("tools")
        assertTrue((0 until tools.length()).any { tools.getJSONObject(it).optString("type") == "web_search_20250305" })
    }

    @Test
    fun `plain text is the answer, a search on the way is skipped`() {
        server.enqueue(sse(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"x\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Laravel is a **PHP**"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" framework."}}""",
            """{"type":"content_block_stop","index":1}""", *end("end_turn")))
        assertEquals(CloudResult.Answer("Laravel is a PHP framework."), agent.decide("k", "claude-opus-5-5", "what is Laravel", emptyList(), emptyList(), call))
    }

    @Test
    fun `refusal is not understood, a rejected key says so`() {
        server.enqueue(sse(start, *end("refusal")))
        assertEquals(CloudResult.NotUnderstood, agent.decide("k", "claude-opus-5-5", "…", emptyList(), emptyList(), call))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try {
            agent.decide("k", "claude-opus-5-5", "ac on", emptyList(), emptyList(), call)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.startsWith("Anthropic key rejected (401)"))
        }
    }
}
