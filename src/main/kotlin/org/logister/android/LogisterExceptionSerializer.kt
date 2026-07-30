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
    )

    @Throws(Exception::class)
    private fun serialize(
        throwable: Throwable,
        policy: LogisterExceptionDataPolicy,
        depth: Int,
        seen: MutableSet<Throwable>,
    ): JSONObject {
        val exception = JSONObject()
        exception.put("type", throwable.javaClass.name)
        if (policy == LogisterExceptionDataPolicy.FULL) {
            exception.put("message", throwable.message)
        }
        exception.put("stacktrace", frames(throwable.stackTrace, MAX_STACK_FRAMES))

        val cause = throwable.cause
        if (
            policy == LogisterExceptionDataPolicy.FULL &&
            cause != null &&
            cause !== throwable &&
            depth < MAX_CAUSE_DEPTH &&
            seen.add(throwable)
        ) {
            exception.put("cause", serialize(cause, policy, depth + 1, seen))
        }

        return exception
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
    private const val MAX_CAUSE_DEPTH = 5
}
