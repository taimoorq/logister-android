package org.logister.android

import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterHistoricalEnvelopeTest {
    @Test
    fun unknownHistoricalBuildNeverInheritsRelaunchFacts() {
        val transport = HistoricalRecordingTransport()
        val client = client(transport)
        val externalId = "com.acme.shop:exit:1000"
        val event = LogisterEvent.builder("error", "Android process exit: anr")
            .occurredAt("2026-08-09T10:00:00.000Z")
            .attribute("uuid", UUID.nameUUIDFromBytes(externalId.toByteArray()).toString())
            .context("capture_source", "historical_exit")
            .context("error_mechanism", "anr")
            .context("handled", false)
            .context("build_provenance", "unknown")
            .context("diagnostic_external_id", externalId)
            .context(
                "diagnostic",
                JSONObject()
                    .put("external_id", externalId)
                    .put("evidence_kind", "sampled_thread_dump"),
            )
            .context("app", JSONObject().put("package_name", "com.acme.shop"))
            .build()

        client.capture(event)

        val payload = transport.envelope.getJSONObject("event")
        val context = payload.getJSONObject("context")
        assertFalse(payload.has("environment"))
        assertFalse(payload.has("release"))
        assertFalse(context.has("app_version"))
        assertFalse(context.has("build_number"))
        assertFalse(context.has("session"))
        assertFalse(context.has("breadcrumbs"))
        assertEquals("com.acme.shop", context.getJSONObject("app").getString("package_name"))
        assertFalse(context.getJSONObject("app").has("version_name"))
        assertEquals("application_exit_info", payload.getJSONObject("evidence").getString("source"))
        assertEquals("sampled_thread_dump", payload.getJSONObject("evidence").getString("evidence_kind"))
    }

    @Test
    fun knownHistoricalBuildOverridesEveryCurrentBuildAlias() {
        val transport = HistoricalRecordingTransport()
        val client = client(transport)
        val event = LogisterEvent.builder("error", "Android process exit: low_memory_kill")
            .occurredAt("2026-08-09T10:00:00.000Z")
            .context("capture_source", "historical_exit")
            .context("error_mechanism", "low_memory_kill")
            .context("handled", false)
            .context("build_provenance", "process_run_journal")
            .context("app_version", "1.0")
            .context("build_number", "10")
            .context("environment", "staging")
            .context("release", "com.acme.shop@1.0+10")
            .context(
                "app",
                JSONObject()
                    .put("package_name", "com.acme.shop")
                    .put("version_name", "1.0")
                    .put("version_code", "10"),
            )
            .build()

        client.capture(event)

        val payload = transport.envelope.getJSONObject("event")
        val context = payload.getJSONObject("context")
        assertEquals("staging", payload.getString("environment"))
        assertEquals("com.acme.shop@1.0+10", payload.getString("release"))
        assertEquals("1.0", context.getString("app_version"))
        assertEquals("10", context.getString("build_number"))
        assertEquals("1.0", context.getJSONObject("app").getString("version_name"))
        assertTrue(payload.getJSONObject("context").getJSONObject("error").getBoolean("fatal"))
    }

    private fun client(transport: LogisterTransport): LogisterClient = LogisterClient
        .builder(
            LogisterTokenProvider { LogisterToken("short-lived", System.currentTimeMillis() / 1_000 + 300) },
            "https://logister.example",
        )
        .includeDeviceContext(false)
        .packageName("com.acme.shop")
        .appVersion("2.0")
        .buildNumber("20")
        .environment("production")
        .release("com.acme.shop@2.0+20")
        .transport(transport)
        .executor(Executors.newSingleThreadExecutor())
        .build()
}

private class HistoricalRecordingTransport : LogisterTransport {
    lateinit var envelope: JSONObject

    override fun send(
        endpoint: String,
        mobileIngestToken: String,
        envelope: JSONObject,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): LogisterResponse {
        this.envelope = JSONObject(envelope.toString())
        return LogisterResponse(202)
    }
}
