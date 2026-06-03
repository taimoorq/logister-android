package org.logister.android

import org.json.JSONArray
import org.json.JSONObject

internal object LogisterExceptionSerializer {
    @Throws(Exception::class)
    fun serialize(throwable: Throwable): JSONObject {
        val exception = JSONObject()
        exception.put("type", throwable.javaClass.name)
        exception.put("message", throwable.message)
        exception.put("stacktrace", frames(throwable.stackTrace))

        val cause = throwable.cause
        if (cause != null && cause !== throwable) {
            exception.put("cause", serialize(cause))
        }

        return exception
    }

    @Throws(Exception::class)
    private fun frames(stackTrace: Array<StackTraceElement>?): JSONArray {
        val frames = JSONArray()
        if (stackTrace == null) {
            return frames
        }

        for (element in stackTrace) {
            val frame = JSONObject()
            frame.put("class", element.className)
            frame.put("method", element.methodName)
            frame.put("file", element.fileName)
            frame.put("line", element.lineNumber)
            frames.put(frame)
        }
        return frames
    }
}

