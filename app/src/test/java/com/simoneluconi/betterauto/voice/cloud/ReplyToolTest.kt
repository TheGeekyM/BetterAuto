package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Test

/** Claude replies to a message through the reading tool (the API's 20 strict tools are all taken). */
class ReplyToolTest {
    private fun r(vararg input: Pair<String, Any?>) = CloudTools.result("read_notifications", mapOf(*input)) { _, _ -> null }

    @Test
    fun `reading or replying`() {
        assertEquals(CloudResult.Command(VoiceCommand.ReadNotifications), r())
        assertEquals(CloudResult.Command(VoiceCommand.Reply("Ahmed Ali", "أنا جاي")), r("reply_to" to "Ahmed Ali", "text" to " أنا جاي "))
        assertEquals(CloudResult.Command(VoiceCommand.Reply(null, "On my way")), r("reply_to" to "last", "text" to "On my way"))
        assertEquals(CloudResult.Command(VoiceCommand.Reply("Mario", "")), r("reply_to" to "Mario"))
    }
}
