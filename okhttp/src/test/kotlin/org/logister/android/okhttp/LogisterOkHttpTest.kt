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
