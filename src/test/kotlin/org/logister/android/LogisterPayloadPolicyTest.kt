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
    fun recursivelyRedactsSensitiveKeysAndScrubsBearerAndUrlValues() {
        val envelope = JSONObject()
            .put("uuid", "safe")
            .put("authorization", "Bearer top-secret")
            .put(
                "context",
                JSONObject()
                    .put("api-key", "nested-secret")
                    .put("message", "Open https://example.test/path?token=secret&page=2#fragment with Bearer abc123")
                    .put("items", JSONArray().put(JSONObject().put("password", "hidden")).put("visible")),
            )

        val sanitized = requireNotNull(sanitizeEnvelope(envelope, LogisterPayloadPolicy.DEFAULT))
        val context = sanitized.getJSONObject("context")

        assertEquals("safe", sanitized.getString("uuid"))
        assertEquals("[REDACTED]", sanitized.getString("authorization"))
        assertEquals("[REDACTED]", context.getString("api-key"))
        assertEquals("[REDACTED]", context.getJSONArray("items").getJSONObject(0).getString("password"))
        assertEquals(
            "Open https://example.test/path?token=[REDACTED]&page=2 with Bearer [REDACTED]",
            context.getString("message"),
        )
        assertFalse(sanitized.toString().contains("nested-secret"))
        assertFalse(sanitized.toString().contains("top-secret"))
        assertFalse(sanitized.toString().contains("hidden"))
        assertEquals("visible", context.getJSONArray("items").getString(1))
    }

    @Test
    fun sharedKeyRuleMatchesAfterStrippingSeparatorsAndByFragment() {
        val keys = listOf(
            "accessToken", "apiKey", "x-api-key", "refreshToken", "clientSecret", "idToken",
            "X-CSRF-Token", "Set-Cookie", "session_token", "Proxy-Authorization", "private_key",
            "credit_card", "card-number", "cvv", "ssn", "pwd", "signature", "mobile_ingest_token",
            "githubPassword", "dbCredentialName", "stripe_secret_ref",
        )
        val context = JSONObject()
        keys.forEach { context.put(it, "leak-$it") }
        context.put("user_name", "visible").put("tokenizer", "redacted-by-fragment").put("session_id", "s-1")

        val sanitized = requireNotNull(
            sanitizeEnvelope(JSONObject().put("event", eventWith(context)), LogisterPayloadPolicy.DEFAULT),
        ).getJSONObject("event").getJSONObject("context")

        keys.forEach { assertEquals(it, "[REDACTED]", sanitized.getString(it)) }
        assertEquals("[REDACTED]", sanitized.getString("tokenizer"))
        assertEquals("visible", sanitized.getString("user_name"))
        assertEquals("s-1", sanitized.getString("session_id"))
    }

    @Test
    fun configuredKeysAreNormalizedLikeTheBuiltInList() {
        val policy = LogisterPayloadPolicy.builder().addSensitiveKey("Customer-Email").build()
        val context = JSONObject().put("customer_email", "a@example.test").put("customerEmail", "b@example.test")

        val sanitized = requireNotNull(sanitizeEnvelope(JSONObject().put("event", eventWith(context)), policy))
            .getJSONObject("event").getJSONObject("context")

        assertEquals("[REDACTED]", sanitized.getString("customer_email"))
        assertEquals("[REDACTED]", sanitized.getString("customerEmail"))
    }

    @Test
    fun scrubsCredentialValuePatternsInFreeText() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        val text = listOf(
            "auth Basic dXNlcjpwYXNzd29yZA==",
            "jwt $jwt",
            "key lgst_live_0123456789abcdef",
            "see https://user:pass@example.test/cb?code=1&access_token=abc&state=x",
            "Basic setup stays readable",
        ).joinToString(" | ")

        val scrubbed = scrubSensitiveText(text, LogisterPayloadPolicy.DEFAULT)

        assertEquals(
            listOf(
                "auth Basic [REDACTED]",
                "jwt [REDACTED]",
                "key [REDACTED]",
                "see https://[REDACTED]@example.test/cb?code=1&access_token=[REDACTED]&state=x",
                "Basic setup stays readable",
            ).joinToString(" | "),
            scrubbed,
        )
    }

    @Test
    fun aFullContextBudgetNeverDropsRequiredEventFields() {
        val context = JSONObject()
        repeat(1_200) { index -> context.put("key_%04d".format(index), "value-$index") }

        val sanitized = requireNotNull(
            sanitizeEnvelope(JSONObject().put("event", eventWith(context)), LogisterPayloadPolicy.DEFAULT),
        ).getJSONObject("event")

        assertEquals("00000000-0000-4000-8000-000000000000", sanitized.getString("uuid"))
        assertEquals("error", sanitized.getString("event_type"))
        assertEquals("checkout failed", sanitized.getString("message"))
        assertEquals("sdk", sanitized.getJSONObject("evidence").getString("source"))
        val sanitizedContext = sanitized.getJSONObject("context")
        assertTrue(sanitizedContext.getBoolean("_truncated"))
        assertEquals("android", sanitizedContext.getString("platform"))
        assertTrue(sanitizedContext.length() <= LogisterPayloadPolicy.DEFAULT.maxCollectionItems + 1)
    }

    @Test
    fun anOversizedEnvelopeDegradesToFitTheByteBudget() {
        val policy = LogisterPayloadPolicy.builder()
            .limits(12, 1_000, 32_768, 16 * 1_024)
            .build()
        val context = JSONObject().put("platform", "android")
        repeat(40) { index -> context.put("blob_$index", "x".repeat(2_000)) }

        val sanitized = requireNotNull(sanitizeEnvelope(JSONObject().put("event", eventWith(context)), policy))

        assertTrue(sanitized.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1_024)
        val event = sanitized.getJSONObject("event")
        assertEquals("00000000-0000-4000-8000-000000000000", event.getString("uuid"))
        assertEquals("checkout failed", event.getString("message"))
        assertTrue(event.getJSONObject("context").getBoolean("_truncated"))
    }

    @Test
    fun rejectsAnEnvelopeWithoutAnEventThatCannotBeReducedBelowTheByteBudget() {
        val policy = LogisterPayloadPolicy.builder()
            .limits(12, 1_000, 32_768, 16 * 1_024)
            .build()
        val envelope = JSONObject()
        repeat(200) { index -> envelope.put("value_$index", "x".repeat(200)) }

        assertNull(sanitizeEnvelope(envelope, policy))
    }

    private fun eventWith(context: JSONObject): JSONObject = JSONObject()
        .put("uuid", "00000000-0000-4000-8000-000000000000")
        .put("event_type", "error")
        .put("message", "checkout failed")
        .put("occurred_at", "2026-10-23T00:00:00.000Z")
        .put("evidence", JSONObject().put("source", "sdk").put("kind", "error"))
        .put("context", context.put("platform", "android"))
}
