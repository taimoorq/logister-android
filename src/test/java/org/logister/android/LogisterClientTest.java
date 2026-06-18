package org.logister.android;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class LogisterClientTest {
    @Test
    public void captureMetricSendsMetricEnvelope() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = testClient(transport)
                .environment("production")
                .release("1.0.0+42")
                .repository("acme/android")
                .commitSha("abc1234")
                .branch("main")
                .service("com.example.app")
                .build();

        LogisterResponse response = client.captureMetricAsync(
                "cart.item_count",
                3,
                "count",
                LogisterEventOptions.builder()
                        .sessionId("session-123")
                        .context("screen_name", "Checkout")
                        .build()
        ).get();

        assertTrue(response.isAccepted());
        assertEquals("https://logister.example/api/v1/ingest_events", transport.endpoint);
        assertEquals("test-token", transport.apiKey);

        JSONObject event = transport.envelope.getJSONObject("event");
        JSONObject context = event.getJSONObject("context");

        assertEquals("metric", event.getString("event_type"));
        assertEquals("cart.item_count", event.getString("message"));
        assertEquals("production", event.getString("environment"));
        assertEquals("1.0.0+42", event.getString("release"));
        assertEquals("android", context.getString("platform"));
        assertEquals("com.example.app", context.getString("service"));
        assertEquals("acme/android", context.getString("repository"));
        assertEquals("abc1234", context.getString("commit_sha"));
        assertEquals("main", context.getString("branch"));
        assertEquals("session-123", context.getString("session_id"));
        assertEquals("Checkout", context.getString("screen_name"));
        assertEquals(3.0, context.getDouble("value"), 0.001);
        assertEquals("count", context.getString("unit"));
    }

    @Test
    public void captureSpanSendsSpanFields() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = testClient(transport).build();

        client.captureSpanAsync(
                LogisterSpan.builder("trace-123", "GET /checkout", 42.5)
                        .spanId("span-456")
                        .parentSpanId("span-root")
                        .kind("http")
                        .status("ok")
                        .context("screen_name", "Checkout")
                        .build()
        ).get();

        JSONObject event = transport.envelope.getJSONObject("event");
        JSONObject context = event.getJSONObject("context");

        assertEquals("span", event.getString("event_type"));
        assertEquals("trace-123", event.getString("trace_id"));
        assertEquals("span-456", event.getString("span_id"));
        assertEquals("span-root", event.getString("parent_span_id"));
        assertEquals("GET /checkout", event.getString("name"));
        assertEquals("http", event.getString("kind"));
        assertEquals(42.5, event.getDouble("duration_ms"), 0.001);
        assertEquals("android", context.getString("platform"));
        assertEquals("Checkout", context.getString("screen_name"));
    }

    @Test
    public void captureExceptionIncludesExceptionContext() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = testClient(transport).build();

        client.captureExceptionAsync(new IllegalStateException("bad checkout")).get();

        JSONObject event = transport.envelope.getJSONObject("event");
        JSONObject context = event.getJSONObject("context");
        JSONObject exception = context.getJSONObject("exception");

        assertEquals("error", event.getString("event_type"));
        assertEquals("error", event.getString("level"));
        assertEquals("java.lang.IllegalStateException", exception.getString("type"));
        assertEquals("bad checkout", exception.getString("message"));
        assertNotNull(exception.getJSONArray("stacktrace"));
    }

    private static LogisterClient.Builder testClient(CapturingTransport transport) {
        return LogisterClient.builder("test-token", "https://logister.example")
                .includeDeviceContext(false)
                .transport(transport)
                .executor(Executors.newSingleThreadExecutor());
    }

    private static final class CapturingTransport implements LogisterTransport {
        private String endpoint;
        private String apiKey;
        private JSONObject envelope;

        @Override
        public LogisterResponse send(
                String endpoint,
                String apiKey,
                JSONObject envelope,
                int connectTimeoutMs,
                int readTimeoutMs
        ) {
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.envelope = envelope;
            return new LogisterResponse(201, "{\"status\":\"accepted\"}");
        }
    }
}
