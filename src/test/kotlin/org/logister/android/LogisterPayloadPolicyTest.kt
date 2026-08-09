package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterPayloadPolicyTest {
    @Test
    fun recursivelyRemovesSensitiveKeysAndScrubsBearerAndUrlValues() {
        val envelope = JSONObject()
            .put("uuid", "safe")
            .put("authorization", "Bearer top-secret")
            .put(
                "context",
                JSONObject()
                    .put("api-key", "nested-secret")
                    .put("message", "Open https://example.test/path?token=secret#fragment with Bearer abc123")
                    .put("items", JSONArray().put(JSONObject().put("password", "hidden")).put("visible")),
            )

        val sanitized = requireNotNull(sanitizeEnvelope(envelope, LogisterPayloadPolicy.DEFAULT))

        assertFalse(sanitized.has("authorization"))
        assertFalse(sanitized.getJSONObject("context").has("api-key"))
        assertFalse(sanitized.toString().contains("nested-secret"))
        assertFalse(sanitized.toString().contains("top-secret"))
        assertFalse(sanitized.toString().contains("abc123"))
        assertFalse(sanitized.toString().contains("?token="))
        assertTrue(sanitized.toString().contains("[REDACTED]"))
        assertEquals("visible", sanitized.getJSONObject("context").getJSONArray("items").getString(1))
    }

    @Test
    fun rejectsAnEnvelopeThatStillExceedsTheFinalByteBudget() {
        val policy = LogisterPayloadPolicy.builder()
            .limits(12, 1_000, 32_768, 16 * 1_024)
            .build()
        val envelope = JSONObject().put("value", "x".repeat(20 * 1_024))

        assertNull(sanitizeEnvelope(envelope, policy))
    }
}
