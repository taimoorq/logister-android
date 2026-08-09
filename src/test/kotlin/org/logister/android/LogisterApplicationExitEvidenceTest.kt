package org.logister.android

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterApplicationExitEvidenceTest {
    @Test
    fun buildsCanonicalSampledMemoryAndStructuredAnrThreads() {
        val trace = """
            ----- pid 4312 at 2026-08-09 10:00:00 -----
            Cmd line: com.acme.shop
            "worker" prio=5 tid=12 Waiting
              at java.lang.Object.wait(Native Method)
              at com.acme.shop.Cache.await(Cache.kt:17)
            "main" prio=5 tid=1 Blocked
              at com.acme.shop.CheckoutStore.commit(CheckoutStore.kt:84)
              - waiting to lock <0x1234> held by thread 12
              at android.app.ActivityThread.main(ActivityThread.java:9000)
            bearer should-not-be-copied
        """.trimIndent()

        val diagnostic = LogisterApplicationExitEvidence.build(
            externalId = "com.acme.shop:4312:1000:6:100",
            mechanism = "anr",
            reason = 6,
            importance = 100,
            status = 0,
            pssKb = 2048,
            rssKb = 4096,
            packageName = "com.acme.shop",
            anrTrace = ByteArrayInputStream(trace.toByteArray()),
        )

        assertEquals("sampled_thread_dump", diagnostic.getString("evidence_kind"))
        assertEquals(
            2L * 1024L * 1024L,
            diagnostic.getJSONObject("measurements").getJSONObject("last_pss").getLong("value"),
        )
        assertEquals("bytes", diagnostic.getJSONObject("measurements").getJSONObject("last_rss").getString("unit"))

        val threadDump = diagnostic.getJSONObject("thread_dump")
        val threads = threadDump.getJSONArray("threads")
        val main = threads.getJSONObject(0)
        assertEquals("main", main.getString("name"))
        assertTrue(main.getBoolean("attributed"))
        assertEquals(2, main.getJSONArray("frames").length())
        assertTrue(main.getJSONArray("frames").getJSONObject(0).getBoolean("in_app"))
        assertEquals("CheckoutStore.kt", main.getJSONArray("frames").getJSONObject(0).getString("file"))
        assertFalse(main.getJSONArray("frames").getJSONObject(1).getBoolean("in_app"))
        assertFalse(threadDump.toString().contains("waiting to lock"))
        assertFalse(threadDump.toString().contains("should-not-be-copied"))
    }

    @Test
    fun omitsZeroSamplesAndUnsupportedRawTraceEvidence() {
        val diagnostic = LogisterApplicationExitEvidence.build(
            externalId = "com.acme.shop:4312:1000:3:400",
            mechanism = "low_memory_kill",
            reason = 3,
            importance = 400,
            status = 9,
            pssKb = 0,
            rssKb = 0,
            packageName = "com.acme.shop",
            anrTrace = ByteArrayInputStream("arbitrary native payload".toByteArray()),
        )

        assertEquals("termination_metadata", diagnostic.getString("evidence_kind"))
        assertFalse(diagnostic.has("measurements"))
        assertFalse(diagnostic.has("thread_dump"))
    }

    @Test
    fun boundsOversizedTraceInput() {
        val repeatedFrames = buildString {
            append("\"main\" prio=5 tid=1 Blocked\n")
            repeat(4_000) { index ->
                append("  at com.acme.shop.Worker.frame$index(Worker.kt:${index + 1})\n")
            }
        }

        val threadDump = LogisterApplicationExitEvidence.parseAnrTrace(
            ByteArrayInputStream(repeatedFrames.toByteArray()),
            "com.acme.shop",
        )

        requireNotNull(threadDump)
        assertTrue(threadDump.getBoolean("truncated"))
        assertTrue(threadDump.getJSONArray("threads").getJSONObject(0).getJSONArray("frames").length() <= 64)
        assertTrue(threadDump.toString().length < repeatedFrames.length)
    }
}
