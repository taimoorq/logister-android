package org.logister.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogisterProcessRunJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesTheBuildThatWasRunningAtTheExitTime() {
        val journal = LogisterProcessRunJournal(temporaryFolder.newFile("process-runs.json"))
        journal.record(run(startedAt = 1_000, version = "1.0", build = "10"))
        journal.record(run(startedAt = 2_000, version = "2.0", build = "20"))

        assertNull(journal.buildFor(999, "com.acme.shop"))
        assertEquals("10", journal.buildFor(1_999, "com.acme.shop")?.versionCode)
        assertEquals("20", journal.buildFor(2_500, "com.acme.shop")?.versionCode)
    }

    @Test
    fun keepsOnlyTheBoundedNewestRunsAndTreatsInvalidStorageAsUnknown() {
        val file = temporaryFolder.newFile("bounded-runs.json")
        val journal = LogisterProcessRunJournal(file, maxRuns = 2)
        journal.record(run(startedAt = 1_000, version = "1.0", build = "10"))
        journal.record(run(startedAt = 2_000, version = "2.0", build = "20"))
        journal.record(run(startedAt = 3_000, version = "3.0", build = "30"))

        assertNull(journal.buildFor(1_500, "com.acme.shop"))
        assertEquals("20", journal.buildFor(2_500, "com.acme.shop")?.versionCode)
        file.writeText("not json")
        assertNull(journal.buildFor(4_000, "com.acme.shop"))
    }

    private fun run(startedAt: Long, version: String, build: String): LogisterProcessRun =
        LogisterProcessRun(
            startedAtMillis = startedAt,
            packageName = "com.acme.shop",
            versionName = version,
            versionCode = build,
            environment = "production",
            release = "com.acme.shop@$version+$build",
            processName = "com.acme.shop",
        )
}
