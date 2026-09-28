package org.logister.android.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.logister.android.*
import java.net.ServerSocket
import java.util.concurrent.Executors

class LogisterOkHttpTest {
    @Test fun redirectsStripUnapprovedHeadersAndPreserveAllowedTrace() {
        val executor = Executors.newSingleThreadExecutor()
        val client = LogisterClient.builder(LogisterTokenProvider { LogisterToken("synthetic", System.currentTimeMillis() / 1000 + 300) }, "https://logister.example")
            .includeDeviceContext(false).executor(executor).transport(LogisterTransport { _, _, _, _, _ -> LogisterResponse(202) }).build()
        try {
            for (allowSecond in listOf(false, true)) {
                ServerSocket(0).use { first -> ServerSocket(0).use { second ->
                    val headers = List(2) { mutableMapOf<String, String>() }
                    val firstOrigin = "http://localhost:${first.localPort}"
                    val secondOrigin = "http://localhost:${second.localPort}"
                    val a = serve(first, headers[0], "302 Found", "Location: $secondOrigin/final\r\n")
                    val b = serve(second, headers[1], "503 Unavailable", "")
                    val origins = if (allowSecond) listOf(firstOrigin, secondOrigin) else listOf(firstOrigin)
                    val http = LogisterOkHttpInterceptor(client, origins).install(OkHttpClient.Builder()).build()
                    http.newCall(Request.Builder().url("$firstOrigin/start").build()).execute().use { response ->
                        assertEquals(503, response.code)
                        a.join(5000); b.join(5000)
                        val original = LogisterTraceContext.parse(headers[0]["traceparent"])!!
                        if (allowSecond) {
                            val final = LogisterTraceContext.parse(headers[1]["traceparent"])!!
                            assertEquals(original.traceId, final.traceId)
                            assertNotEquals(original.spanId, final.spanId)
                            assertEquals(final.spanId, response.request.tag(LogisterTraceContext::class.java)!!.spanId)
                        } else {
                            assertNull(headers[1]["traceparent"])
                            assertNull(headers[1]["x-request-id"])
                        }
                    }
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                } }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun capturesDnsFailureBeforeNetworkInterceptorsWithoutDuplicates() {
        val envelopes = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
        val captured = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val client = LogisterClient.builder(LogisterTokenProvider { LogisterToken("synthetic", System.currentTimeMillis() / 1000 + 300) }, "https://logister.example")
            .includeDeviceContext(false).executor(executor).transport(LogisterTransport { _, _, envelope, _, _ -> envelopes.add(envelope); captured.countDown(); LogisterResponse(202) }).build()
        val http = LogisterOkHttpInterceptor(client, listOf("https://api.example")).install(OkHttpClient.Builder()
            .dns { throw java.net.UnknownHostException("synthetic DNS failure") }).build()
        try {
            try {
                http.newCall(Request.Builder().url("https://api.example/work").build()).execute().close()
                fail("Expected DNS failure")
            } catch (error: LogisterOkHttpException) {
                assertTrue(error.cause is java.net.UnknownHostException)
                assertTrue(captured.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, envelopes.size)
                val event = envelopes.first().getJSONObject("event")
                val context = event.getJSONObject("context")
                assertEquals(error.traceContext.traceId, context.getString("trace_id"))
                val metadata = context.getJSONObject("http")
                assertEquals("dns", metadata.getString("failure_kind"))
                assertEquals(1, metadata.getInt("attempt"))
                assertFalse(metadata.has("status_code"))
            }
        } finally {
            executor.shutdownNow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun responseMetadataKeepsSuccessfulAndClientErrorResponsesUnchanged() {
        val envelopes = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
        val captured = java.util.concurrent.CountDownLatch(2)
        val executor = Executors.newSingleThreadExecutor()
        val client = LogisterClient.builder(LogisterTokenProvider { LogisterToken("synthetic", System.currentTimeMillis() / 1000 + 300) }, "https://logister.example")
            .includeDeviceContext(false).executor(executor).transport(LogisterTransport { _, _, envelope, _, _ -> envelopes.add(envelope); captured.countDown(); LogisterResponse(202) }).build()
        try {
            for (status in listOf(200, 429)) {
                ServerSocket(0).use { server ->
                    val responder = serve(server, mutableMapOf(), "$status Result", "")
                    val origin = "http://localhost:${server.localPort}"
                    val http = LogisterOkHttpInterceptor(client, listOf(origin)).install(OkHttpClient.Builder()).build()
                    try {
                        http.newCall(Request.Builder().url("$origin/work").build()).execute().use { response ->
                            assertEquals(status, response.code)
                            assertNotNull(response.request.tag(LogisterTraceContext::class.java))
                        }
                        responder.join(5000)
                    } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
                }
            }
            assertTrue(captured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val metadata = envelopes.map { it.getJSONObject("event").getJSONObject("context").getJSONObject("http") }
            assertFalse(metadata.first { it.getInt("status_code") == 200 }.has("failure_kind"))
            assertEquals("http", metadata.first { it.getInt("status_code") == 429 }.getString("failure_kind"))
            metadata.forEach { assertEquals("response_headers", it.getString("duration_scope")); assertEquals(1, it.getInt("attempt")) }
        } finally { executor.shutdownNow() }
    }

    private fun serve(server: ServerSocket, headers: MutableMap<String, String>, status: String, extra: String): Thread = Thread {
        server.soTimeout = 5000
        server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            reader.readLine()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1].trim()
            }
            socket.getOutputStream().write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
    }.apply { start() }
}
