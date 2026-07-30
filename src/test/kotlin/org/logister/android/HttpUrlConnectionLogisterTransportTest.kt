package org.logister.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpUrlConnectionLogisterTransportTest {
    @Test
    fun userAgentUsesTheSharedSdkVersion() {
        val userAgent = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ingest") { exchange ->
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val response =
                HttpUrlConnectionLogisterTransport().send(
                    endpoint = "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                    mobileIngestToken = "short-lived",
                    envelope = JSONObject().put("event", JSONObject()),
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                )

            assertEquals(202, response.statusCode)
            assertEquals("logister-android/$LOGISTER_ANDROID_SDK_VERSION", userAgent.get())
        } finally {
            server.stop(0)
        }
    }
}
