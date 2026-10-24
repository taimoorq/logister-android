package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

internal object LogisterExceptionSerializer {
    @Throws(Exception::class)
    fun serialize(
        throwable: Throwable,
        policy: LogisterExceptionDataPolicy,
    ): JSONObject = serialize(
        throwable = throwable,
        policy = policy,
        depth = 0,
        seen = Collections.newSetFromMap(IdentityHashMap()),
        enclosingTrace = null,
        frameBudget = intArrayOf(MAX_CHAIN_FRAMES),
    )

    @Throws(Exception::class)
    private fun serialize(
        throwable: Throwable,
        policy: LogisterExceptionDataPolicy,
        depth: Int,
        seen: MutableSet<Throwable>,
        enclosingTrace: Array<StackTraceElement>?,
        frameBudget: IntArray,
    ): JSONObject {
        val exception = JSONObject()
        exception.put("type", throwable.javaClass.name)
        if (policy == LogisterExceptionDataPolicy.FULL) {
            exception.put("message", throwable.message)
        }
        val trace = throwable.stackTrace ?: emptyArray()
        // Like Throwable.printStackTrace, a cause omits the trailing frames it shares with the
        // exception that wraps it, so a long chain stays within the payload item budget.
        val inCommon = if (enclosingTrace == null) 0 else framesInCommon(trace, enclosingTrace)
        val limit = minOf(MAX_STACK_FRAMES, frameBudget[0], trace.size - inCommon).coerceAtLeast(0)
        frameBudget[0] -= limit
        exception.put("stacktrace", frames(trace.copyOfRange(0, trace.size - inCommon), limit))
        if (inCommon > 0) exception.put("frames_in_common", inCommon)

        val cause = throwable.cause
        if (
            policy == LogisterExceptionDataPolicy.FULL &&
            cause != null &&
            cause !== throwable &&
            depth < MAX_CAUSE_DEPTH &&
            seen.add(throwable)
        ) {
            exception.put("cause", serialize(cause, policy, depth + 1, seen, trace, frameBudget))
        }

        return exception
    }

    private fun framesInCommon(
        trace: Array<StackTraceElement>,
        enclosingTrace: Array<StackTraceElement>,
    ): Int {
        var traceIndex = trace.size - 1
        var enclosingIndex = enclosingTrace.size - 1
        while (traceIndex >= 0 && enclosingIndex >= 0 && trace[traceIndex] == enclosingTrace[enclosingIndex]) {
            traceIndex -= 1
            enclosingIndex -= 1
        }
        return trace.size - 1 - traceIndex
    }

    @Throws(Exception::class)
    private fun frames(
        stackTrace: Array<StackTraceElement>?,
        limit: Int,
    ): JSONArray {
        val frames = JSONArray()
        if (stackTrace == null) {
            return frames
        }

        for (element in stackTrace.take(limit)) {
            val frame = JSONObject()
            frame.put("class", element.className)
            frame.put("method", element.methodName)
            frame.put("file", element.fileName)
            frame.put("line", element.lineNumber)
            frames.put(frame)
        }
        return frames
    }

    private const val MAX_STACK_FRAMES = 100
    private const val MAX_CHAIN_FRAMES = 150
    private const val MAX_CAUSE_DEPTH = 5
}
