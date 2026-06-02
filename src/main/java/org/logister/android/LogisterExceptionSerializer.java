package org.logister.android;

import org.json.JSONArray;
import org.json.JSONObject;

final class LogisterExceptionSerializer {
    private LogisterExceptionSerializer() {
    }

    static JSONObject serialize(Throwable throwable) throws Exception {
        JSONObject exception = new JSONObject();
        exception.put("type", throwable.getClass().getName());
        exception.put("message", throwable.getMessage());
        exception.put("stacktrace", frames(throwable.getStackTrace()));

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            exception.put("cause", serialize(cause));
        }

        return exception;
    }

    private static JSONArray frames(StackTraceElement[] stackTrace) throws Exception {
        JSONArray frames = new JSONArray();
        if (stackTrace == null) {
            return frames;
        }

        for (StackTraceElement element : stackTrace) {
            JSONObject frame = new JSONObject();
            frame.put("class", element.getClassName());
            frame.put("method", element.getMethodName());
            frame.put("file", element.getFileName());
            frame.put("line", element.getLineNumber());
            frames.put(frame);
        }
        return frames;
    }
}
