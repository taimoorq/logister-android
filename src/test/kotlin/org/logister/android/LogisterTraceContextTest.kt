package org.logister.android

import org.junit.Assert.*
import org.junit.Test

class LogisterTraceContextTest {
    @Test fun wireContextAndAllowlist() {
        val parent = LogisterTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00", "request-1")!!
        val child = parent.child()
        assertEquals(parent.traceId, child.traceId)
        assertEquals(parent.spanId, child.parentSpanId)
        assertNotEquals(parent.spanId, child.spanId)
        assertEquals("00", child.flags)
        val origins = listOf("https://api.example.test")
        assertEquals(child.traceparent, child.headersFor("https://api.example.test:443/path", origins)["traceparent"])
        listOf("http://api.example.test", "https://api.example.test.evil", "https://api.example.test:444", "https://user@api.example.test").forEach {
            assertTrue(child.headersFor(it, origins).isEmpty())
        }
        assertEquals(child.spanId, child.eventOptions().context["span_id"])
    }
    @Test fun invalidHeadersAndConcurrentAttempts() {
        assertNull(LogisterTraceContext.parse("00-${"0".repeat(32)}-00f067aa0ba902b7-01"))
        assertNull(LogisterTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-${"0".repeat(16)}-01"))
        assertNull(LogisterTraceContext.parse("garbage"))
        val parent = LogisterTraceContext.create()
        val attempts = (1..100).map { parent.child() }
        assertEquals(100, attempts.map { it.spanId }.toSet().size)
        assertEquals(setOf(parent.traceId), attempts.map { it.traceId }.toSet())
    }
}
