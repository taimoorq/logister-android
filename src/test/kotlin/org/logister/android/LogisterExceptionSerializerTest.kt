package org.logister.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterExceptionSerializerTest {
    @Test
    fun safePolicyOmitsMessagesAndCauseChain() {
        val throwable = IllegalStateException(
            "bearer secret-value",
            IllegalArgumentException("private nested detail"),
        )

        val serialized = LogisterExceptionSerializer.serialize(
            throwable,
            LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE,
        )

        assertEquals(IllegalStateException::class.java.name, serialized.getString("type"))
        assertTrue(serialized.getJSONArray("stacktrace").length() > 0)
        assertFalse(serialized.has("message"))
        assertFalse(serialized.has("cause"))
        assertFalse(serialized.toString().contains("secret-value"))
        assertFalse(serialized.toString().contains("private nested detail"))
    }

    @Test
    fun fullPolicyKeepsBoundedDiagnosticDetail() {
        val throwable = IllegalStateException(
            "checkout failed",
            IllegalArgumentException("bad item"),
        )

        val serialized = LogisterExceptionSerializer.serialize(
            throwable,
            LogisterExceptionDataPolicy.FULL,
        )

        assertEquals("checkout failed", serialized.getString("message"))
        assertEquals("bad item", serialized.getJSONObject("cause").getString("message"))
        assertTrue(serialized.getJSONArray("stacktrace").length() <= 100)
    }

    @Test
    fun causesOmitFramesSharedWithTheEnclosingTraceAndTheChainIsBounded() {
        val common = Array(90) { StackTraceElement("com.acme.Common", "frame$it", "Common.kt", it + 1) }
        var throwable: Throwable = IllegalArgumentException("root")
        throwable.stackTrace = unique("Root") + common
        repeat(8) { level ->
            throwable = IllegalStateException("level $level", throwable).apply {
                stackTrace = unique("Level$level") + common
            }
        }

        val serialized = LogisterExceptionSerializer.serialize(throwable, LogisterExceptionDataPolicy.FULL)

        assertEquals(100, serialized.getJSONArray("stacktrace").length())
        assertFalse(serialized.has("frames_in_common"))
        val cause = serialized.getJSONObject("cause")
        assertEquals(10, cause.getJSONArray("stacktrace").length())
        assertEquals(90, cause.getInt("frames_in_common"))
        var frames = 0
        var current: org.json.JSONObject? = serialized
        while (current != null) {
            frames += current.getJSONArray("stacktrace").length()
            current = current.optJSONObject("cause")
        }
        assertTrue(frames <= 150)
    }

    private fun unique(name: String): Array<StackTraceElement> =
        Array(10) { StackTraceElement("com.acme.$name", "frame$it", "$name.kt", it + 1) }
}
