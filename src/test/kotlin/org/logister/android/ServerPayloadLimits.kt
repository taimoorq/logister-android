package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue

/** A port of the Rails `TelemetryPayloadLimits` walk, used to assert SDK output is accepted. */
internal object ServerPayloadLimits {
    fun assertAccepted(event: JSONObject) {
        val walk = Walk()
        walk.visit(event, depth = 0)
        val message = event.optString("message")
        assertTrue("message is ${message.utf8()} bytes", message.utf8() <= 64 * 1_024)
        assertTrue("event has ${walk.keys} object keys", walk.keys <= 512)
        assertTrue("event estimate is ${walk.bytes} bytes", walk.bytes <= 1_000_000)
        assertTrue("event depth is ${walk.maxDepth}", walk.maxDepth <= 16)
        assertTrue("an array has ${walk.maxArray} items", walk.maxArray <= 1_000)
        assertTrue("a string has ${walk.maxString} bytes", walk.maxString <= 256 * 1_024)
    }

    private class Walk {
        var keys = 0
        var bytes = 0L
        var maxDepth = 0
        var maxArray = 0
        var maxString = 0

        fun visit(value: Any?, depth: Int) {
            maxDepth = maxOf(maxDepth, depth)
            when (value) {
                is JSONObject -> {
                    keys += value.length()
                    for (key in value.keys()) {
                        bytes += key.utf8() + 4
                        visit(value.opt(key), depth + 1)
                    }
                }
                is JSONArray -> {
                    maxArray = maxOf(maxArray, value.length())
                    for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
                }
                is String -> {
                    maxString = maxOf(maxString, value.utf8())
                    bytes += value.utf8() + 4
                }
                null, JSONObject.NULL -> bytes += 8
                else -> bytes += value.toString().utf8() + 4
            }
        }
    }

    private fun String.utf8(): Int = toByteArray(Charsets.UTF_8).size
}
