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
}
