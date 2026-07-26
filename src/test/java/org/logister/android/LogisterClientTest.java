package org.logister.android;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        assertEquals("mobile-token-1", transport.mobileIngestToken);

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

    @Test
    public void captureExceptionUsesVersionedCanonicalMobileContext() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = testClient(transport)
                .packageName("com.acme.shop")
                .appVersion("2.0.0")
                .buildNumber("50")
                .buildType("release")
                .breadcrumbs(10)
                .build();
        client.addBreadcrumb(
                LogisterBreadcrumb.builder("Checkout opened")
                        .category("navigation")
                        .data("screen", "Checkout")
                        .build()
        );

        client.captureExceptionAsync(
                new IllegalStateException("bad checkout"),
                LogisterEventOptions.builder()
                        .mechanism("unhandled_exception")
                        .handled(false)
                        .inForeground(true)
                        .screenName("CheckoutActivity")
                        .sessionId("session-123")
                        .build()
        ).get();

        JSONObject context = transport.envelope.getJSONObject("event").getJSONObject("context");
        assertEquals(2, context.getInt("telemetry_schema_version"));
        assertEquals("com.acme.shop", context.getJSONObject("app").getString("package_name"));
        assertEquals("2.0.0", context.getJSONObject("app").getString("version_name"));
        assertEquals("50", context.getJSONObject("app").getString("version_code"));
        assertEquals("CheckoutActivity", context.getJSONObject("app").getString("screen"));
        assertEquals(true, context.getJSONObject("app").getBoolean("in_foreground"));
        assertEquals("session-123", context.getJSONObject("session").getString("id"));
        assertEquals("unhandled_exception", context.getJSONObject("error").getString("mechanism"));
        assertEquals(false, context.getJSONObject("error").getBoolean("handled"));
        assertEquals(1, context.getJSONArray("breadcrumbs").length());
    }

    @Test
    public void applicationBackedFeaturesRequireExplicitApplication() {
        try {
            testClient(new CapturingTransport())
                    .installationTracking(true, 30)
                    .offlineQueue(true, 10, 64 * 1024)
                    .build();
            fail("Expected application requirement");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("application is required"));
        }
    }

    @Test
    public void providerFailureDoesNotSendRequest() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = LogisterClient.builder(
                        () -> {
                            throw new IllegalStateException("token issuer unavailable");
                        },
                        "https://logister.example"
                )
                .includeDeviceContext(false)
                .transport(transport)
                .executor(Executors.newSingleThreadExecutor())
                .build();

        try {
            client.captureMessageAsync("one").get();
            fail("Expected token provider failure");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof IllegalStateException);
        }

        assertEquals(0, transport.sendCount);
    }

    @Test
    public void expiredTokenDoesNotSendRequest() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        LogisterClient client = LogisterClient.builder(
                        new SequenceTokenProvider(new LogisterToken("expired-token", nowEpochSeconds() - 1)),
                        "https://logister.example"
                )
                .includeDeviceContext(false)
                .transport(transport)
                .executor(Executors.newSingleThreadExecutor())
                .build();

        try {
            client.captureMessageAsync("one").get();
            fail("Expected expired token failure");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, transport.sendCount);
    }

    private static LogisterClient.Builder testClient(CapturingTransport transport) {
        return LogisterClient.builder(
                        new SequenceTokenProvider(new LogisterToken("mobile-token-1", nowEpochSeconds() + 300)),
                        "https://logister.example"
                )
                .includeDeviceContext(false)
                .transport(transport)
                .executor(Executors.newSingleThreadExecutor());
    }

    private static final class CapturingTransport implements LogisterTransport {
        private String endpoint;
        private String mobileIngestToken;
        private JSONObject envelope;
        private int sendCount;

        @Override
        public LogisterResponse send(
                String endpoint,
                String mobileIngestToken,
                JSONObject envelope,
                int connectTimeoutMs,
                int readTimeoutMs
        ) {
            sendCount += 1;
            this.endpoint = endpoint;
            this.mobileIngestToken = mobileIngestToken;
            this.envelope = envelope;
            return new LogisterResponse(201, "{\"status\":\"accepted\"}");
        }
    }

    private static final class SequenceTokenProvider implements LogisterTokenProvider {
        private final ArrayDeque<LogisterToken> tokens;

        private SequenceTokenProvider(LogisterToken... tokens) {
            this.tokens = new ArrayDeque<>(Arrays.asList(tokens));
        }

        @Override
        public LogisterToken fetchToken() {
            return tokens.size() > 1 ? tokens.removeFirst() : tokens.peekFirst();
        }
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000;
    }
}
