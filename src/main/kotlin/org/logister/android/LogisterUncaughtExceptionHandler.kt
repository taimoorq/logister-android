package org.logister.android

import java.util.concurrent.atomic.AtomicBoolean

internal class LogisterUncaughtExceptionHandler(
    private val capture: (Throwable) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    private val reporting = AtomicBoolean(false)

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (reporting.compareAndSet(false, true)) {
            try {
                capture(throwable)
            } catch (_: Throwable) {
                // Telemetry must never replace Android's existing crash behavior.
            } finally {
                reporting.set(false)
            }
        }
        delegate.uncaughtException(thread, throwable)
    }
}
