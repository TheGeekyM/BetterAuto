package com.simoneluconi.betterauto.voice.cloud

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigDisabled
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.anthropic.models.models.ModelListParams
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.voice.VoiceCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration

/**
 * Claude understands what the online assistant heard, through Anthropic's Java SDK (beta Messages API, streamed):
 * the transcript in, one call of a fixed set of strict tools out ([CloudTools]), acted on as soon as that call is
 * complete in the stream rather than at the end of the message. Effort low and, where the model allows, no extended
 * thinking: this is a voice turn. The system prompt and tools stay the same between calls (cached); web search only
 * for questions that need fresh information.
 */
// ponytail: one limit for every turn; a web search needs ~10 s, commands rarely reach Claude (fast path)
class ClaudeAgent(private val baseUrl: String? = null, private val timeoutMs: Long = 15_000) {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun client(key: String): AnthropicClient {
        if (key != clientKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofMillis(timeoutMs))
                // A voice turn can't wait for retries: on failure the phone's own parser takes over
                .maxRetries(0)
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()
            clientKey = key
        }
        return client!!
    }

    /** Opens the connection while the driver talks (a model listing, no tokens); failures don't matter. */
    fun prewarm(key: String) {
        runCatching { client(key).models().list(ModelListParams.builder().limit(1L).build()) }
    }

    /**
     * [contacts]: the phone's contact names, so a name heard in Arabic can be matched to one written in Latin letters
     * (and back). [call] turns a name into the phone's call command. [firstToken] is called once, at the first event.
     */
    fun decide(
        key: String, model: String, transcript: String, contacts: List<String>, history: List<Conversation.Turn>,
        call: (String, String?) -> VoiceCommand?, persona: String = "", firstToken: () -> Unit = {},
    ): CloudResult {
        var params = params(model, transcript, contacts, history, persona)
        var first = true
        repeat(MAX_ROUNDS) {
            val accumulator = BetaMessageAccumulator.create()
            val tools = HashMap<Long, String>()
            val inputs = HashMap<Long, StringBuilder>()
            val text = StringBuilder()
            var stop: BetaStopReason? = null
            try {
                client(key).beta().messages().createStreaming(params).use { stream ->
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        val event = events.next()
                        accumulator.accumulate(event)
                        if (first) {
                            first = false
                            firstToken()
                        }
                        event.contentBlockStart().orElse(null)?.let { start ->
                            start.contentBlock().toolUse().orElse(null)?.let { use ->
                                tools[start.index()] = use.name()
                                inputs[start.index()] = StringBuilder()
                            }
                        }
                        event.contentBlockDelta().orElse(null)?.let { d ->
                            d.delta().inputJson().orElse(null)?.let { inputs[d.index()]?.append(it.partialJson()) }
                            d.delta().text().orElse(null)?.let { text.append(it.text()) }
                        }
                        // The tool call is complete: act now, the rest of the message is not needed
                        event.contentBlockStop().orElse(null)?.let { s ->
                            val name = tools[s.index()]
                            if (name != null) {
                                val input = toMap(JSONObject(inputs[s.index()].toString().ifBlank { "{}" }))
                                EventLog.log("Voice online: Claude calls $name ${input.keys}")
                                return CloudTools.result(name, input, call)
                            }
                        }
                        event.messageDelta().orElse(null)?.let { stop = it.delta().stopReason().orElse(null) }
                    }
                }
            } catch (e: AnthropicServiceException) {
                throw IOException(explain(e))
            }
            when (stop) {
                BetaStopReason.REFUSAL, BetaStopReason.MAX_TOKENS -> {
                    EventLog.log("Voice online: Claude stopped (${stop?.asString()})")
                    return CloudResult.NotUnderstood
                }
                // The server's search loop paused: its turn goes back as it is and it carries on
                BetaStopReason.PAUSE_TURN -> {
                    params = params.toBuilder().addMessage(accumulator.message()).build()
                    return@repeat
                }
                else -> return CloudTools.answer(text.toString())
            }
        }
        return CloudResult.NotUnderstood
    }

    fun params(model: String, transcript: String, contacts: List<String>, history: List<Conversation.Turn>, persona: String = ""): MessageCreateParams {
        val haiku = model == "claude-haiku-5-5"
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .systemOfBetaTextBlockParams(listOf(
                BetaTextBlockParam.builder()
                    .text(systemPrompt(contacts, persona))
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .build(),
            ))
            .toolChoice(BetaToolChoiceAuto.builder().disableParallelToolUse(true).build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .apply {
                // Haiku 5.5 without thinking (accepted at effort low). Sonnet and Opus think adaptively, which at effort
                // low is short or skipped; Sonnet's between_tools would be faster still, but it is Sonnet-only and the
                // server's refusal fallback re-runs the same body on another model
                if (haiku) thinking(BetaThinkingConfigDisabled.builder().build())
                history.forEach { addUserMessage(it.user); addAssistantMessage(it.assistant) }
                addUserMessage(transcript)
                CloudTools.specs.forEach { addTool(tool(it)) }
                // The dynamic-filtering web search is for Opus/Sonnet; Haiku 5.5 is not listed for it
                if (haiku) addTool(BetaWebSearchTool20250305.builder().maxUses(2L).build())
                else addTool(BetaWebSearchTool20260209.builder().maxUses(2L).build())
                // Refusal fallback on the server; Claude Haiku 5.5 has none (the field is a 400 there)
                if (!haiku) addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
            }
            .build()
    }

    fun close() = synchronized(this) {
        client?.close()
        client = null
        clientKey = null
    }

    private fun tool(spec: CloudTools.Spec): BetaTool = BetaTool.builder()
        .name(spec.name)
        .description(spec.description)
        .strict(true)
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(BetaTool.InputSchema.Properties.builder().apply {
                    spec.properties.forEach { (name, schema) -> putAdditionalProperty(name, JsonValue.from(schema)) }
                }.build())
                .required(spec.required)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build(),
        )
        .build()

    private fun explain(e: AnthropicServiceException): String {
        val detail = e.apiMessage().orElse("").take(200)
        return when (e.statusCode()) {
            401 -> "Anthropic key rejected (401) $detail"
            403 -> "Anthropic key not allowed (403) $detail"
            429 -> "Anthropic rate limit or quota (429) $detail"
            else -> "Claude: HTTP ${e.statusCode()} $detail"
        }.trim()
    }

    companion object {
        private const val MAX_ROUNDS = 3

        fun systemPrompt(contacts: List<String>, persona: String = ""): String = buildString {
            appendLine("You are the voice assistant of BetterAuto, the driver's car screen. You get a speech-to-text " +
                "transcript in Egyptian Arabic, English, Italian or a mix; it may have recognition mistakes: read it " +
                "generously, as something said to a car assistant.")
            appendLine("Finish every turn by calling exactly one tool. Commands (music, navigation, calls, the car's " +
                "climate and volume, screens) go to their tool. Questions, explanations, advice and small talk go to " +
                "answer, briefly, for someone driving, in their language and dialect. Use web_search first only when " +
                "the question needs fresh information (news, prices, scores, weather). not_understood only when the " +
                "transcript is unintelligible.")
            appendLine("Earlier turns of the conversation may come before the current one: use them for follow-ups.")
            if (persona.isNotBlank()) appendLine(persona)
            if (contacts.isNotEmpty()) {
                appendLine("The phone's contacts (for call_contact, write the name as listed here):")
                appendLine(contacts.joinToString(", "))
            }
        }

        private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { value(o.opt(it)) }

        private fun value(v: Any?): Any? = when (v) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { value(v.opt(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
