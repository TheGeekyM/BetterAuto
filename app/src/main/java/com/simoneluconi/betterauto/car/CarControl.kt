package com.simoneluconi.betterauto.car

import com.simoneluconi.betterauto.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The car's own controls (climate, the head unit's volume, tyre pressures), through our head unit app (Open
 * Headunit, Geely build), which announces the vendor extension service [VENDOR_NAME]. One JSON object per message
 * both ways: a request `{"id":7,"op":"hvac.temp","delta":1}`, its answer `{"id":7,"ok":true,"value":23.0}`; the
 * head unit opens with `{"op":"hello","caps":[...]}`. Any other head unit: [caps] stays empty and requests say no.
 */
object CarControl {
    const val VENDOR_NAME = "betterauto.car"
    private const val ANSWER_MS = 3_000L

    private val _caps = MutableStateFlow<Set<String>>(emptySet())
    /** What the car can do now: "hvac", "volume", "tires". */
    val caps: StateFlow<Set<String>> = _caps

    @Volatile private var sender: ((String) -> Unit)? = null
    private val nextId = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    fun attach(send: (String) -> Unit) {
        sender = send
        EventLog.log("Car control: channel open, waiting for the head unit's hello")
    }

    fun detach() {
        sender = null
        _caps.value = emptySet()
        waiting.values.forEach { it.cancel() }
        waiting.clear()
    }

    fun onMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrElse {
            EventLog.log("Car control: not JSON (${text.take(40)})")
            return
        }
        if (json.optString("op") == "hello") {
            val caps = json.optJSONArray("caps")
            _caps.value = (0 until (caps?.length() ?: 0)).map { caps!!.getString(it) }.toSet()
            EventLog.log("Car control: the head unit offers ${_caps.value}")
            return
        }
        waiting.remove(json.optInt("id", -1))?.complete(json)
    }

    /**
     * Asks the head unit for [op] with [fields]; its answer, or null when no head unit of ours is there or it did
     * not answer in time.
     */
    suspend fun request(op: String, fields: JSONObject.() -> Unit = {}): JSONObject? {
        val send = sender ?: return null
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JSONObject>()
        waiting[id] = answer
        val request = JSONObject().put("id", id).put("op", op).apply(fields)
        EventLog.log("Car control: $request")
        runCatching { send(request.toString()) }.onFailure {
            waiting.remove(id)
            return null
        }
        val reply = withTimeoutOrNull(ANSWER_MS) { answer.await() }
        waiting.remove(id)
        EventLog.log("Car control: ${reply ?: "no answer to $op"}")
        return reply
    }
}
