package org.logister.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LogisterUncaughtExceptionHandlerTest {
    @Test
    fun persistsBeforeDelegating() {
        val calls = mutableListOf<String>()
        val handler = LogisterUncaughtExceptionHandler(
            capture = { thread, _ -> calls += "capture:${thread.name}" },
            delegate = Thread.UncaughtExceptionHandler { _, _ -> calls += "delegate" },
        )

        handler.uncaughtException(Thread("checkout-crash"), IllegalStateException("secret"))

        assertEquals(listOf("capture:checkout-crash", "delegate"), calls)
    }

    @Test
    fun telemetryFailureStillDelegatesAndReentrantCallsAreNotRecaptured() {
        var captures = 0
        var delegations = 0
        lateinit var handler: LogisterUncaughtExceptionHandler
        handler = LogisterUncaughtExceptionHandler(
            capture = { _, _ ->
                captures += 1
                handler.uncaughtException(Thread.currentThread(), IllegalStateException("nested"))
                throw AssertionError("telemetry failed")
            },
            delegate = Thread.UncaughtExceptionHandler { _, _ -> delegations += 1 },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("original"))

        assertEquals(1, captures)
        assertEquals(2, delegations)
    }
}
