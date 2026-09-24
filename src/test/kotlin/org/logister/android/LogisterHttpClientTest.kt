package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogisterHttpClientTest {
    @Test fun requestWireAndFailureIdentity() {
        val envelopes = Collections.synchronizedList(mutableListOf<JSONObject>())
        val exported = CountDownLatch(2)
        val executor = Executors.newSingleThreadExecutor()
        val client = LogisterClient.builder(LogisterTokenProvider { LogisterToken("synthetic-mobile-token", System.currentTimeMillis() / 1000 + 300) }, "https://logister.example")
            .includeDeviceContext(false).environment("production").release("mobile-test")
            .executor(executor).transport(LogisterTransport { _, _, envelope, _, _ -> envelopes.add(envelope); exported.countDown(); LogisterResponse(202) }).build()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val received = mutableMapOf<String, String>()
            val responseThread = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) received[parts[0].lowercase()] = parts[1].trim()
                    }
                    socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            try {
                val origin = "http://localhost:${server.localPort}"
                val http = LogisterHttpClient(client, listOf(origin))
                val result = http.execute("$origin/work") { it.responseCode }
                responseThread.join(5000)
                val trace = result.traceContext!!
                assertEquals(503, result.value)
                assertEquals(trace.traceparent, received["traceparent"])
                client.captureExceptionAsync(IllegalStateException("backend failed"), trace.eventOptions()).get(5, TimeUnit.SECONDS)
                assertTrue(exported.await(5, TimeUnit.SECONDS))
                envelopes.forEach { envelope ->
                    assertEquals(trace.traceId, envelope.getJSONObject("event").getJSONObject("context").getString("trace_id"))
                    assertEquals(trace.spanId, envelope.getJSONObject("event").getJSONObject("context").getString("span_id"))
                }
                System.getenv("LOGISTER_CORRELATION_FIXTURES")?.let { directory ->
                    File(directory, "android.json").writeText(JSONObject().put("headers", JSONObject(mapOf("traceparent" to trace.traceparent, "x-request-id" to trace.requestId)))
                        .put("envelopes", JSONArray(envelopes)).toString(2))
                }
            } finally { executor.shutdownNow() }
        }
    }
}
